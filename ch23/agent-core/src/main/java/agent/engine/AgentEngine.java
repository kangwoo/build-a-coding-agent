package agent.engine;

import agent.context.compact.ContextManager;
import agent.cost.CostTracker;
import agent.exec.CancellationToken;
import agent.exec.ToolOrchestrator;
import agent.llm.*;
import agent.llm.AssistantMessageAccumulator;   // provider-중립(6장)
import agent.message.ContentBlock;
import agent.message.ContentBlock.ToolResultBlock;
import agent.message.ContentBlock.ToolUseBlock;
import agent.message.Message;
import agent.message.Usage;
import agent.session.TranscriptStore;
import agent.tool.*;
import agent.tool.builtin.FileStateCache;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 대화 한 건의 상태(메시지·누적 토큰·파일 캐시)를 소유하고 에이전트 루프를 돌린다.
 * LlmClient는 생성자로 주입받는다 — 엔진은 어떤 provider인지 모른다(5장의 약속).
 */
public final class AgentEngine {

    private static final int MAX_TURNS = 20;
    private static final int MAX_TOKENS = 4096;

    private final LlmClient model;          // 주입 — provider를 모른다
    private final ToolRegistry tools;
    private final ToolOrchestrator orchestrator;   // 13장 — 병렬/직렬 분배 + 순서 보존
    private final List<SystemBlock> systemPrompt;   // 18장 — 정적/동적 경계를 든 블록 리스트
    private final String modelId;
    private final AgentServices services;           // 권한·압축·비용·영속성(16·19·20장에서 채워짐)

    private final List<Message> messages = new ArrayList<>();
    private FileStateCache fileState = new FileStateCache();   // final 아님 — /clear가 새 캐시로 교체(21장)
    private final java.nio.file.Path cwd = java.nio.file.Path.of("").toAbsolutePath();
    private boolean contextInjected = false;   // 18장 — 프로젝트 컨텍스트 1회 주입
    private Usage totalUsage = Usage.EMPTY;
    private Message currentQuestion;   // 이 턴의 사용자 질문 — 턴 중간 풀 압축의 원문 재부착용(19장)
    private final AtomicBoolean turnActive = new AtomicBoolean(false);   // 재진입 가드 — 턴은 한 번에 하나

    public AgentEngine(LlmClient model, String modelId, ToolRegistry tools,
                       String systemPrompt, AgentServices services) {
        // String 편의 오버로드 — 정적 한 블록으로 감싼다(서브에이전트(23장)·테스트가 쓴다).
        this(model, modelId, tools, LlmRequest.systemBlocks(systemPrompt), services);
    }

    public AgentEngine(LlmClient model, String modelId, ToolRegistry tools,
                       List<SystemBlock> systemPrompt, AgentServices services) {
        this.model = model; this.modelId = modelId; this.tools = tools;
        this.systemPrompt = systemPrompt; this.services = services;
        // 필드 이니셜라이저가 아니라 생성자 본문에서 — this.tools 주입 뒤라야 null이 아니다.
        // 게이트(16장)·훅(17장)은 12장 이음매 AgentServices에서 흘러온다.
        this.orchestrator = new ToolOrchestrator(tools, services.gate(), services.hooks());
    }

    // 스레드 계약: runLoop는 별도 프로듀서 스레드에서 돌며 아래 상태를 직접 쓴다.
    // messages()는 읽기 전용 뷰다 — submit()이 돌려준 스트림을 다 소비한 뒤에만 읽는다
    // (EventStreams 큐 전달이 happens-before를 만든다). 너무 이른 submit() 재호출은 가드가 잡는다.
    public List<Message> messages() { return Collections.unmodifiableList(messages); }
    public Usage totalUsage() { return totalUsage; }

    /** 대화를 초기화한다(21장 /clear). 다음 submit()이 프로젝트 컨텍스트(18장)를 다시 주입한다. */
    public void clearConversation() {
        if (turnActive.get())                        // 턴 진행 중의 초기화는 submit() 재진입과 같은 계약 위반
            throw new IllegalStateException("턴 진행 중에는 대화를 초기화할 수 없음");
        messages.clear();
        fileState = new FileStateCache();            // Read-before-Write 캐시도 새 대화 기준으로(9~10장)
        totalUsage = Usage.EMPTY;
        currentQuestion = null;                      // 지운 대화의 질문을 붙들지 않는다(19장 재부착용)
        contextInjected = false;
        ContextManager ctxMgr = services.contextManager();
        if (ctxMgr != null) ctxMgr.reset();          // 압축 서킷 브레이커도 새 대화 기준으로(19장)
    }

    /** 디스크에서 읽은 이전 메시지로 대화를 복원한다(20장). 컨텍스트 재주입은 막는다. */
    public void resume(List<Message> previous) {
        messages.addAll(previous);
        contextInjected = true;   // 18장 컨텍스트는 복원된 첫 메시지에 이미 있다 — 재주입 안 함
    }

    /**
     * 메타 텍스트(예: 22장 스킬 목록)를 &lt;system-reminder&gt; user 메시지로 1회 주입한다.
     * 사용자가 직접 친 게 아니므로 injected=true. 영속화(20장)도 함께 한다.
     */
    public void injectSystemReminder(String reminder) {
        var msg = new Message.UserMessage(
                UUID.randomUUID().toString(), java.time.Instant.now(),
                List.of(new ContentBlock.TextBlock(reminder)), true);
        messages.add(msg);
        record(msg);
    }

    /** 사용자 입력 한 건을 받아 루프를 돌리며 이벤트를 스트리밍한다. */
    public EventStream<AgentEvent> submit(String userInput, CancellationToken cancel) {
        if (!turnActive.compareAndSet(false, true))      // 이전 턴 스트림을 다 소비하기 전의 재호출은 버그다
            throw new IllegalStateException("이전 턴이 끝나기 전에 submit()이 다시 호출됨");
        try {
            if (!contextInjected) {   // 18장 — AGENT.md+날짜를 첫 <system-reminder> user 메시지로(한 번)
                agent.context.ProjectContext.build(cwd).ifPresent(m -> { messages.add(m); record(m); });
                contextInjected = true;
            }
            Message.UserMessage user = Message.UserMessage.of(userInput);
            currentQuestion = user;   // 턴 중간 풀 압축이 원문 재부착할 질문(19장)
            messages.add(user);
            record(user);             // 여는 프롬프트도 영속화(20장)
            return EventStreams.fromProducer(sink -> {
                try { runLoop(sink, cancel); }
                finally { turnActive.set(false); }           // 프로듀서 종료 = 이 턴의 상태 쓰기 끝
            });
        } catch (RuntimeException e) {
            turnActive.set(false);                       // 프로듀서 기동 전에 죽으면 여기서 해제한다
            throw e;
        }
    }

    /** transcript가 있을 때만 기록한다(20장). null이면 no-op. */
    private void record(Message m) {
        TranscriptStore transcript = services.transcript();
        if (transcript != null) transcript.record(m);
    }

    private void runLoop(EventStreams.Sink<AgentEvent> sink, CancellationToken cancel) {
        ContextManager ctxMgr = services.contextManager();   // 19장 — null이면 압축 안 함
        CostTracker cost = services.costTracker();            // 19장 — null이면 비용 추적 안 함
        int turn = 0;
        while (true) {
            if (cancel.isCancelled()) {
                sink.emit(new AgentEvent.TurnFinished(new Transition.Aborted(cancel.reason())));
                return;
            }
            if (++turn > MAX_TURNS) {
                sink.emit(new AgentEvent.TurnFinished(new Transition.MaxTurns(MAX_TURNS)));
                return;
            }

            // 컨텍스트 압축(19장): 매 턴 마이크로, 임계치 초과면 풀(LLM 직접 호출 — 루프 안 거침)
            if (ctxMgr != null) {
                ctxMgr.microCompact(messages);
                if (ctxMgr.shouldCompact(messages)) {
                    // 진행 중 질문은 엔진이 안다 — submit()이 담아 둔 것을 넘긴다(관리자가 이력에서 재파생 X).
                    ctxMgr.fullCompact(messages, Optional.of(currentQuestion), cancel).ifPresent(compacted -> {
                        messages.clear(); messages.addAll(compacted);   // in-place 교체 — messages() 읽기 전용 뷰가 이 리스트를 비춘다
                        sink.emit(new AgentEvent.Compacted());          // 이력 치환을 사용자에게 알린다
                    });
                }
            }

            // ① 모델 스트리밍 호출
            var acc = new AssistantMessageAccumulator();
            LlmRequest req = new LlmRequest(modelId, systemPrompt, messages,
                    toolSpecs(), MAX_TOKENS, ThinkingConfig.disabled(), 1.0);
            try (EventStream<StreamEvent> stream = model.stream(req, cancel)) {
                for (StreamEvent e : stream) {
                    if (e instanceof StreamEvent.ApiError err) {   // 누산기에 넣기 전에 가로챈다 — 넣으면 예외를 던진다(6장)
                        sink.emit(new AgentEvent.TurnFinished(new Transition.ModelError(err.message())));
                        return;
                    }
                    acc.accept(e);
                    if (e instanceof StreamEvent.BlockDelta bd
                            && bd.delta() instanceof StreamEvent.TextDelta td) {
                        sink.emit(new AgentEvent.AssistantTextDelta(td.text()));
                    }
                }
            }

            // 취소로 스트림이 끊겼다면 provider가 닫기 프레임 합성을 건너뛰어 tool_use JSON이 잘려 있을 수 있다.
            // build() 전에 Aborted로 끝낸다 — assistant 메시지를 추가하지 않으니 고아 tool_use도 없다.
            if (cancel.isCancelled()) {
                sink.emit(new AgentEvent.TurnFinished(new Transition.Aborted(cancel.reason())));
                return;
            }

            // ② assistant 메시지 확정, tool_use 추출
            Message.AssistantMessage assistant = acc.build();
            messages.add(assistant);
            record(assistant);                                          // 영속화(20장)
            totalUsage = totalUsage.plus(assistant.usage());
            if (cost != null) cost.record(modelId, assistant.usage());   // 비용 기록(19장)

            List<ToolUseBlock> toolUses = assistant.content().stream()
                    .filter(b -> b instanceof ToolUseBlock)
                    .map(b -> (ToolUseBlock) b)
                    .toList();

            // ③ 도구가 없으면 종료
            if (toolUses.isEmpty()) {
                sink.emit(new AgentEvent.TurnFinished(new Transition.Completed()));
                return;
            }

            // ④ 도구 실행 → tool_result (병렬/직렬 분배 + 순서 보존, 불변식: 모든 tool_use에 결과)
            List<ToolResultBlock> results = orchestrator.runAll(toolUses, ctx(cancel),
                    new ToolOrchestrator.Listener() {
                        @Override public void started(ToolUseBlock u) {
                            sink.emit(new AgentEvent.ToolStarted(u.name(), u.input()));
                        }
                        @Override public void finished(ToolUseBlock u, boolean isError) {
                            sink.emit(new AgentEvent.ToolFinished(u.name(), isError));
                        }
                    });

            // ⑤ tool_result를 user 메시지로 (results는 toolUses와 같은 순서)
            List<ContentBlock> blocks = new ArrayList<>(results);   // List<ToolResultBlock> → List<ContentBlock> 업캐스트
            Message.UserMessage toolResults = Message.UserMessage.ofBlocks(blocks);
            messages.add(toolResults);
            record(toolResults);                                        // 영속화(20장)
        }
    }

    private ToolContext ctx(CancellationToken cancel) {
        return new ToolContext(cwd, cancel, fileState);
    }

    private List<ToolSpec> toolSpecs() {
        List<ToolSpec> specs = new ArrayList<>();
        for (Tool<?, ?> t : tools.all()) {
            specs.add(new ToolSpec(t.name(), t.description(), t.inputSchema()));
        }
        return specs;
    }
}
