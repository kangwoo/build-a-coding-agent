package agent.cli.repl;

import agent.cli.bootstrap.LlmClients;
import agent.cli.bootstrap.Session;
import agent.cli.bootstrap.WorkspaceTrust;
import agent.cli.config.Prices;
import agent.cli.permission.JLinePermissionPrompt;
import agent.cli.render.Renderer;
import agent.command.Command;
import agent.command.CommandContext;
import agent.command.CommandRegistry;
import agent.command.DirectResult;
import agent.command.SlashCommands;
import agent.context.SystemPromptBuilder;
import agent.context.compact.ContextManager;
import agent.cost.CostTracker;
import agent.engine.AgentEvent;
import agent.engine.AgentServices;
import agent.engine.AgentEngine;
import agent.engine.Transition;
import agent.exec.CancellationToken;
import agent.hook.HookConfigLoader;
import agent.hook.HookRunner;
import agent.llm.LlmClient;
import agent.llm.StreamCancelledException;
import agent.llm.SystemBlock;
import agent.message.ContentBlock;
import agent.permission.PermissionContext;
import agent.permission.PermissionMode;
import agent.permission.RuleBasedGate;
import agent.session.SessionContext;
import agent.session.TranscriptStore;
import agent.skill.SkillLoader;
import agent.skill.SkillRegistry;
import agent.skill.SkillTool;
import agent.subagent.AgentDefinition;
import agent.subagent.AgentTool;
import agent.subagent.EngineSubagentRunner;
import agent.subagent.SubagentRunner;
import agent.subagent.SubagentTools;
import agent.tool.ToolRegistry;
import agent.tool.builtin.BuiltinTools;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Repl {
    private static final String DEFAULT_MODEL = "gpt-5.4-mini";           // 상위 모델은 "gpt-5.4" (최신 id로 교체 가능)

    private final Session session;
    private final String provider;
    private final String resumeId;  // null이면 새 세션, 아니면 이어서(20장 --resume)
    private AgentEngine engine;     // 대화 상태를 소유한다. 권한 프롬프트가 터미널을 필요로 해 run()에서 조립한다.

    private final CommandRegistry commands = CommandRegistry.withBuiltins();  // 21장 — /help·/cost·/clear·/exit·/quit
    private CommandContext commandContext;   // 엔진·비용 추적기를 잇는 어댑터. engine·cost가 생기는 run()에서 조립.
    private String oneShot;   // null이 아니면 비대화형 1회 실행 — run()이 READ 루프 대신 이 프롬프트 하나만 처리한다

    public Repl(Session session, String provider, String resumeId) {
        this.session = session;
        this.provider = provider;
        this.resumeId = resumeId;
    }

    public void run() throws IOException {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            Renderer renderer = new Renderer(terminal);

            // 권한 게이트 조립: 규칙 기반 + JLine 대화형 프롬프트(onChange 영속 연결은 하지 않는다 — 부록 D.1.1 참고).
            LlmClient llm = LlmClients.forProvider(provider);
            var perms = new PermissionContext(PermissionMode.DEFAULT, List.of(), () -> {});
            var gate = new RuleBasedGate(perms, new JLinePermissionPrompt(reader));
            // 훅 배선(17장): .agent/settings.json의 hooks를 읽고, 훅이 있으면 신뢰부터 확인한다.
            // 남의 저장소의 훅 자동 실행은 RCE다 — 신뢰 장부는 워크스페이스 밖(~/.agent/trusted.json).
            var hookConfig = HookConfigLoader.load(session.workingDir().resolve(".agent/settings.json"));
            boolean trusted = hookConfig.isEmpty() || new WorkspaceTrust(reader).confirm(session.workingDir());
            var hooks = new HookRunner(hookConfig, session.workingDir(), () -> trusted);
            // 18장 — 정적/동적 섹션을 SystemBlock 리스트로 조립한 시스템 프롬프트(env는 동적 꼬리).
            // AGENT.md는 엔진이 첫 메시지로 주입.
            List<SystemBlock> systemPrompt =
                    new SystemPromptBuilder().blocks(session.workingDir(), DEFAULT_MODEL);
            // 19장 — gpt-5.4의 1M 윈도우 컨텍스트 압축 + OpenAI 단가표 비용 추적.
            var ctxMgr = new ContextManager(1_000_000, llm, DEFAULT_MODEL);
            var cost = new CostTracker(Prices.OPENAI, Prices.FALLBACK);

            // 20장 — 세션 정체성과 영속성. SessionContext가 switchSession을 소유한다(불변 record인 Session이 아님).
            var sessionContext = new SessionContext(session.workingDir());
            if (resumeId != null) {
                sessionContext.switchSession(resumeId, session.workingDir());   // load보다 먼저(경로 확정)
            }
            try (var transcript = new TranscriptStore(sessionContext)) {
                // 22장 — .agent/skills/<name>/SKILL.md 들을 발견(이름+설명만 eager, 본문은 lazy).
                var skills = new SkillRegistry(
                        SkillLoader.loadFrom(session.workingDir().resolve(".agent/skills")));
                // 내장 도구 + SkillTool. commandContext는 아래에서 채워지므로 필드를 지연 참조하는 위임체로 넘긴다.
                CommandContext lazyCtx = new CommandContext() {
                    @Override public String costSummary() { return commandContext.costSummary(); }
                    @Override public void clearConversation() { commandContext.clearConversation(); }
                    @Override public List<Command> commands() { return commandContext.commands(); }
                };
                // 23장 — 서브에이전트 위임 도구. 서브 풀(SubagentTools.poolFor)은 AgentTool을 제외해 무한 재귀를 막는다.
                SubagentRunner subRunner = new EngineSubagentRunner(llm, DEFAULT_MODEL, SubagentTools::poolFor);
                AgentTool agentTool = new AgentTool(subRunner, Map.of("general", AgentDefinition.GENERAL));
                ToolRegistry tools = BuiltinTools.registry()
                        .register(new SkillTool(skills, lazyCtx))
                        .register(agentTool);

                this.engine = new AgentEngine(llm, DEFAULT_MODEL, tools,
                        systemPrompt, new AgentServices(gate, hooks, ctxMgr, cost, transcript));
                // 21장 — 명령이 에이전트 상태에 닿는 어댑터. clear는 엔진 초기화+세션 회전으로, cost는 추적기로 위임한다.
                this.commandContext = new CommandContext() {
                    @Override public String costSummary() { return cost.summary(); }
                    @Override public void clearConversation() {
                        engine.clearConversation();
                        // 인메모리만 비우면 20장 영속 계층과 어긋난다 — 같은 JSONL에 계속 기록돼
                        // --resume이 "지운" 대화까지 복원한다. 새 sessionId로 회전해 이후 기록을
                        // 새 파일·새 parentUuid 체인으로 보낸다(지운 대화는 옛 세션 파일에 남는다).
                        sessionContext.switchSession(UUID.randomUUID().toString(), session.workingDir());
                        // 스킬 목록 리마인더도 함께 지워졌다 — 재주입(22장). 회전 뒤라 새 세션 파일에 기록된다.
                        if (!skills.all().isEmpty()) engine.injectSystemReminder(skills.listing(1_000_000 / 100));
                    }
                    @Override public List<Command> commands() { return commands.all(); }
                };
                // 22장 — 첫 턴에 스킬 목록(이름+설명만)을 system-reminder로 주입(18장 ProjectContext와 같은 자리).
                //         contextWindow의 1%(토큰이 아니라 문자 수)를 예산으로 — 발견은 가볍게, 본문은 SkillTool 호출 때만.
                if (!skills.all().isEmpty()) {
                    engine.injectSystemReminder(skills.listing(1_000_000 / 100));
                }
                if (resumeId != null) {
                    engine.resume(TranscriptStore.load(sessionContext.transcriptPath()));
                    renderer.system("세션 복원: " + resumeId);
                }

                if (oneShot != null) {                       // 비대화형 1회 — 배너·READ 루프 없이 한 번 처리하고 끝
                    handleInput(oneShot, renderer, terminal);
                    return;
                }
                renderer.banner();

                while (true) {
                    String input;
                    try { input = reader.readLine("› "); }
                    catch (EndOfFileException eof) { break; }            // Ctrl+D → 종료
                    catch (UserInterruptException ui) { continue; }      // Ctrl+C → 현재 줄 버림

                    if (input == null) break;
                    String text = input.strip();
                    if (text.isEmpty()) continue;
                    if (handleInput(text, renderer, terminal)) break;    // ← 명령 vs 일반 입력 분기(21장); /exit·/quit → 종료
                }
            }   // transcript.close() — 잔여 쓰기 flush
            renderer.goodbye();
        }
    }

    /** 비대화형 1회 실행: run()과 같은 조립을 거쳐 프롬프트 하나만 처리하고 바로 끝낸다. */
    public void runOnce(String prompt) throws IOException {
        this.oneShot = prompt;
        run();
    }

    /** 입력 한 줄을 분기한다: 슬래시 커맨드면 runCommand, 아니면 에이전트 루프(12장). 종료 요청이면 true. */
    private boolean handleInput(String input, Renderer renderer, Terminal terminal) {
        var parsed = SlashCommands.parse(input);
        if (parsed.isPresent()) {
            return runCommand(parsed.get(), renderer, terminal);   // 슬래시 커맨드
        }
        converse(renderer, terminal, input);                       // 일반 입력 → 에이전트 루프(12장)
        return false;
    }

    /** 파싱된 슬래시 커맨드를 실행한다. 다이렉트는 즉시 끝, 프롬프트는 모델을 한 턴 돌린다. 종료 요청이면 true. */
    private boolean runCommand(SlashCommands.Parsed p, Renderer renderer, Terminal terminal) {
        Command cmd = commands.find(p.name()).orElse(null);
        if (cmd == null) { renderer.system("알 수 없는 명령: /" + p.name()); return false; }

        switch (cmd) {
            case Command.DirectCommand direct -> {
                switch (direct.call(p.args(), commandContext)) {
                    case DirectResult.Text t -> renderer.system(t.text());
                    case DirectResult.ClearHistory ignored -> renderer.system("대화를 비웠습니다.");
                    case DirectResult.Exit ignored -> { return true; }   // 실제 종료(루프 탈출)는 호출자 몫
                    case DirectResult.Skip ignored -> {}
                }
            }
            case Command.PromptCommand prompt -> {
                // 모델에 주입 후 한 턴 실행 (스킬도 이 경로 — 22장)
                var blocks = prompt.getPrompt(p.args(), commandContext);
                converse(renderer, terminal, blocksToText(blocks));
            }
        }
        return false;
    }

    /** ContentBlock 목록에서 텍스트 블록만 이어붙인다(Renderer.plainText의 블록 버전). */
    private static String blocksToText(List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof ContentBlock.TextBlock t) sb.append(t.text());
        }
        return sb.toString();
    }

    /** 사용자 입력 한 건을 엔진에 넘기고, 흘러나오는 이벤트를 화면에 그린다. 턴 동안 Ctrl+C를 취소로 연결. */
    private void converse(Renderer renderer, Terminal terminal, String input) {
        CancellationToken cancel = CancellationToken.root();

        // 이 턴 동안 Ctrl+C → interrupt 취소
        Terminal.SignalHandler prev = terminal.handle(Terminal.Signal.INT,
                sig -> {
                    cancel.cancel("interrupt");
                    renderer.system("⏹ 중단 중…");
                });
        try {
            try (var events = engine.submit(input, cancel)) {
                for (AgentEvent e : events) {
                    switch (e) {
                        case AgentEvent.AssistantTextDelta d -> renderer.assistantChunk(d.text());
                        case AgentEvent.ToolStarted t -> renderer.toolStarted(t.name());
                        case AgentEvent.ToolFinished t -> renderer.toolFinished(t.name(), t.isError());
                        case AgentEvent.Compacted c -> renderer.system("[이전 대화가 요약되었습니다]");
                        case AgentEvent.TurnFinished tf -> renderTransition(renderer, tf.transition());
                    }
                }
            }
        } catch (StreamCancelledException e) {
            // 소비(이 스레드)가 인터럽트로 끊긴 경우. 예외로 이미 전달된 인터럽트를 플래그로도
            // 남기면 다음 readLine까지 튕기므로 걷어내고, 턴만 접는다.
            Thread.interrupted();
            renderer.system("응답 수신이 중단되었습니다.");
        } catch (IllegalStateException e) {
            // submit() 재진입 가드(12장): 이전 턴 생산자가 close()의 join 상한(5초, 6장)을 넘겨
            // 살아 있으면 다음 입력이 여기로 온다. 잡지 않으면 REPL 프로세스가 통째로 죽는다.
            // 위의 catch가 먼저다 — StreamCancelledException은 이 타입의 하위라, 순서가 바뀌면
            // 취소가 계약 위반으로 잡힌다.
            renderer.system("이전 턴이 아직 정리되지 않았습니다: " + e.getMessage());
        } finally {
            terminal.handle(Terminal.Signal.INT, prev);   // 핸들러 원복
            renderer.newline();
        }
    }

    /** 종료 사유를 화면에 드러낸다(Completed는 조용히, 나머지는 알림 한 줄). */
    private void renderTransition(Renderer renderer, Transition t) {
        switch (t) {
            case Transition.Completed c -> { /* 정상 — 본문 텍스트로 충분 */ }
            case Transition.MaxTurns m -> renderer.system("최대 턴(" + m.turns() + ") 초과로 멈췄습니다.");
            case Transition.ModelError e -> renderer.system("모델 오류: " + e.message());
            case Transition.Aborted a -> renderer.system("취소됨: " + a.reason());
        }
    }
}
