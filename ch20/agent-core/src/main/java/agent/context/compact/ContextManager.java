package agent.context.compact;

import agent.exec.CancellationToken;
import agent.llm.*;
import agent.message.*;
import agent.message.ContentBlock.*;

import java.util.*;
import java.util.concurrent.CancellationException;

public final class ContextManager {

    private static final int BUFFER_TOKENS = 13_000;
    private static final int SUMMARY_RESERVE = 20_000;
    private static final int MAX_OUTPUT = 128_000;     // 모델 최대 출력(gpt-5.4 기준) — 요약 예약 상한
    private static final int RECENT_KEEP = 4;          // 마이크로 압축에서 보존할 최근 메시지 수
    private static final int MAX_FAILURES = 3;

    private final long contextWindow;
    private final LlmClient model;
    private final String modelId;
    private int consecutiveFailures = 0;

    public ContextManager(long contextWindow, LlmClient model, String modelId) {
        this.contextWindow = contextWindow; this.model = model; this.modelId = modelId;
    }

    private long threshold() {
        return contextWindow - Math.min(SUMMARY_RESERVE, MAX_OUTPUT) - BUFFER_TOKENS;
    }

    public boolean shouldCompact(List<Message> messages) {
        if (consecutiveFailures >= MAX_FAILURES) return false;   // 서킷 브레이커
        return TokenEstimator.estimate(messages) > threshold();
    }

    /** 새 대화 기준으로 초기화한다(21장 /clear). 트립된 서킷 브레이커를 풀어 준다. */
    public void reset() { consecutiveFailures = 0; }

    /** 매 턴 가벼운 마이크로 압축: 최근 것 빼고 오래된 tool_result 내용을 비운다(LLM 없음). */
    public void microCompact(List<Message> messages) {
        int cutoff = messages.size() - RECENT_KEEP;
        for (int i = 0; i < cutoff; i++) {
            Message m = messages.get(i);
            if (m instanceof Message.UserMessage u && hasToolResult(u)) {
                messages.set(i, clearToolResults(u));
            }
        }
    }

    /**
     * 풀 압축: 대화를 LLM으로 요약해 [경계 + 요약 + 진행 중 질문]으로 치환. 재귀 가드: 루프를 안 거친다.
     * 진행 중 질문은 이력에서 재파생하지 않고 호출자에게 받는다 — 턴 중간의 엔진은 이 턴의 질문을,
     * 턴 경계 호출자(/compact 커맨드 등)는 빈 값을 넘긴다.
     */
    public Optional<List<Message>> fullCompact(List<Message> messages,
                                               Optional<Message> inProgressQuestion,
                                               CancellationToken cancel) {
        try {
            LlmRequest req = new LlmRequest(modelId, LlmRequest.systemBlocks(COMPACT_SYSTEM),
                    withSummaryRequest(messages), List.of(), SUMMARY_RESERVE,
                    ThinkingConfig.disabled(), 1.0);   // 추론 OFF: OpenAI면 reasoning_effort 미전송 (19.3.1)
            Message.AssistantMessage summary = model.create(req, cancel);   // ← 직접 호출(루프 X)
            if (cancel.isCancelled()) return Optional.empty();   // 취소로 잘린 요약일 수 있다 — 치환도 집계도 않는다
            String text = firstText(summary);

            List<Message> compacted = new ArrayList<>();
            compacted.add(Message.NoticeMessage.of("[이전 대화가 요약되었습니다]"));   // 경계 마커
            compacted.add(new Message.UserMessage(                        // injected=true: 합성 메시지 규약(18장)
                    UUID.randomUUID().toString(), java.time.Instant.now(),
                    List.of(new TextBlock("<summary>\n" + text + "\n</summary>")), true));
            // 턴 중간 발동 대비: 진행 중이던 질문은 요약에 접지 않고 원문 그대로 되붙인다.
            inProgressQuestion.ifPresent(compacted::add);
            // 성공해도 임계치 위면(재부착 질문이 거대한 경우 등) 실패로 센다 — 줄지 않는 압축의 무한 반복 방지.
            consecutiveFailures = TokenEstimator.estimate(compacted) > threshold()
                    ? consecutiveFailures + 1 : 0;
            return Optional.of(compacted);
        } catch (CancellationException e) {
            throw e;                   // 취소는 압축 실패가 아니다 — 서킷 브레이커에 세지 않는다
        } catch (RuntimeException e) {
            consecutiveFailures++;                                          // 실패 누적 → 서킷 브레이커
            return Optional.empty();   // 실패는 빈 값 — 호출자는 원본을 건드리지 않고, 성공만 이력을 치환한다
        }
    }

    // ── 헬퍼 ─────────────────────────────────────────────
    private static final String COMPACT_SYSTEM =
            "You summarize a coding conversation. Preserve: the user's goal, key decisions, "
            + "files touched, and open tasks. Be thorough but compact.";

    private List<Message> withSummaryRequest(List<Message> messages) {
        List<Message> req = new ArrayList<>(messages);
        req.add(Message.UserMessage.of("Summarize our conversation so far for context compaction."));
        return req;
    }

    private static boolean hasToolResult(Message.UserMessage u) {
        return u.content().stream().anyMatch(b -> b instanceof ToolResultBlock);
    }
    private static Message.UserMessage clearToolResults(Message.UserMessage u) {
        List<ContentBlock> cleared = u.content().stream().map(b ->
                b instanceof ToolResultBlock r
                        ? (ContentBlock) new ToolResultBlock(r.toolUseId(),
                                List.of(new TextBlock("[오래된 결과가 비워짐]")), r.isError())   // isError 보존
                        : b).toList();
        return new Message.UserMessage(u.uuid(), u.timestamp(), cleared, u.injected());
    }
    private static String firstText(Message.AssistantMessage m) {
        return m.content().stream().filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).text()).findFirst().orElse("(요약 없음)");
    }
}
