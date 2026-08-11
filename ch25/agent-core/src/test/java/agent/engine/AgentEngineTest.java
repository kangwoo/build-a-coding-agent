package agent.engine;

import agent.exec.CancellationToken;
import agent.llm.*;
import agent.message.*;
import agent.message.ContentBlock.*;
import agent.tool.ToolRegistry;
import agent.tool.builtin.EchoTool;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEngineTest {

    /** 스크립트된 assistant 메시지들을 차례로 내놓는 가짜 모델. */
    static final class FakeModel implements LlmClient {
        private final Deque<Message.AssistantMessage> script;
        FakeModel(List<Message.AssistantMessage> turns) { this.script = new ArrayDeque<>(turns); }

        @Override public EventStream<StreamEvent> stream(LlmRequest req, CancellationToken c) {
            Message.AssistantMessage turn = script.poll();
            return EventStreams.fromProducer(sink -> emit(sink, turn));
        }
        @Override public Message.AssistantMessage create(LlmRequest req, CancellationToken c) { return script.poll(); }
        @Override public LlmCapabilities capabilities() { return new LlmCapabilities(false,false,false,false); }
        @Override public String name() { return "fake"; }

        /** assistant 메시지 1개를 StreamEvent들로 풀어낸다. */
        static void emit(EventStreams.Sink<StreamEvent> sink, Message.AssistantMessage m) {
            sink.emit(new StreamEvent.MessageStart(Usage.EMPTY));
            int i = 0;
            for (ContentBlock b : m.content()) {
                if (b instanceof TextBlock t) {
                    sink.emit(new StreamEvent.BlockStart(i, new TextBlock("")));
                    sink.emit(new StreamEvent.BlockDelta(i, new StreamEvent.TextDelta(t.text())));
                } else if (b instanceof ToolUseBlock u) {
                    sink.emit(new StreamEvent.BlockStart(i, new ToolUseBlock(u.id(), u.name(),
                            Json.MAPPER.createObjectNode())));
                    sink.emit(new StreamEvent.BlockDelta(i,
                            new StreamEvent.InputJsonDelta(u.input().toString())));
                }
                sink.emit(new StreamEvent.BlockStop(i++));
            }
            sink.emit(new StreamEvent.MessageDelta(m.usage(), m.stopReason()));
            sink.emit(new StreamEvent.MessageStop());
        }
    }

    @Test
    void runs_tool_then_answers() {
        var toolUse = new ToolUseBlock("tu_1", "Echo",
                Json.MAPPER.createObjectNode().put("text", "PONG"));
        var turn1 = Message.AssistantMessage.of(List.of(toolUse), Usage.EMPTY, "tool_use");
        var turn2 = Message.AssistantMessage.of(
                List.of(new TextBlock("결과는 PONG 입니다")), Usage.EMPTY, "end_turn");

        var engine = new AgentEngine(new FakeModel(List.of(turn1, turn2)), "m",
                new ToolRegistry().register(new EchoTool()), "sys", AgentServices.defaults());

        List<AgentEvent> events = new ArrayList<>();
        try (var s = engine.submit("핑 보내줘", CancellationToken.none())) {
            s.forEach(events::add);
        }

        // 도구가 실행되고, 그 결과가 다음 턴 메시지에 들어가고, 최종 답변으로 끝났다
        assertThat(events).anyMatch(e -> e instanceof AgentEvent.ToolStarted t && t.name().equals("Echo"));
        assertThat(events).anyMatch(e -> e instanceof AgentEvent.AssistantTextDelta d
                && d.text().contains("PONG"));
        assertThat(events.get(events.size() - 1))
                .isEqualTo(new AgentEvent.TurnFinished(new Transition.Completed()));

        // tool_result가 user 메시지로 대화에 포함됨 (불변식)
        boolean hasToolResult = engine.messages().stream()
                .filter(m -> m instanceof Message.UserMessage)
                .flatMap(m -> m.content().stream())
                .anyMatch(b -> b instanceof ToolResultBlock);
        assertThat(hasToolResult).isTrue();
    }

    @Test
    void unknown_tool_still_pairs_a_tool_result() {
        // 존재하지 않는 도구를 모델이 불러도 고아 tool_use가 생기면 안 된다(불변식).
        var bad = new ToolUseBlock("tu_x", "NoSuchTool", Json.MAPPER.createObjectNode());
        var turn1 = Message.AssistantMessage.of(List.of(bad), Usage.EMPTY, "tool_use");
        var turn2 = Message.AssistantMessage.of(
                List.of(new TextBlock("끝")), Usage.EMPTY, "end_turn");

        var engine = new AgentEngine(new FakeModel(List.of(turn1, turn2)), "m",
                new ToolRegistry(), "sys", AgentServices.defaults());

        List<AgentEvent> events = new ArrayList<>();
        try (var s = engine.submit("도구 불러줘", CancellationToken.none())) {
            s.forEach(events::add);
        }

        // 모든 tool_use(여기선 1개)에 대응하는 tool_result(오류)가 채워졌다
        long toolUseCount = engine.messages().stream()
                .flatMap(m -> m.content().stream())
                .filter(b -> b instanceof ToolUseBlock).count();
        long toolResultCount = engine.messages().stream()
                .flatMap(m -> m.content().stream())
                .filter(b -> b instanceof ToolResultBlock).count();
        assertThat(toolResultCount).isEqualTo(toolUseCount);
        assertThat(events).anyMatch(e -> e instanceof AgentEvent.ToolFinished f && f.isError());
        assertThat(events.get(events.size() - 1))
                .isEqualTo(new AgentEvent.TurnFinished(new Transition.Completed()));
    }

    @Test
    void failed_compaction_keeps_history_and_breaker_stops_retries() {
        // 요약 모델이 항상 던진다 — 압축은 매번 실패. create() 호출 횟수로 시도를 관찰한다.
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        LlmClient failingSummarizer = new LlmClient() {
            public EventStream<StreamEvent> stream(LlmRequest r, CancellationToken c) { throw new UnsupportedOperationException(); }
            public Message.AssistantMessage create(LlmRequest r, CancellationToken c) {
                attempts.incrementAndGet(); throw new RuntimeException("boom");
            }
            public LlmCapabilities capabilities() { return new LlmCapabilities(false,false,false,false); }
            public String name() { return "failing"; }
        };
        // 윈도우 1000 → 임계치 음수 → 매 루프 반복이 압축을 시도한다(서킷 브레이커가 멈출 때까지)
        var ctxMgr = new agent.context.compact.ContextManager(1_000, failingSummarizer, "m");

        var tu1 = new ToolUseBlock("tu_1", "Echo", Json.MAPPER.createObjectNode().put("text", "A"));
        var tu2 = new ToolUseBlock("tu_2", "Echo", Json.MAPPER.createObjectNode().put("text", "B"));
        var engine = new AgentEngine(new FakeModel(List.of(
                Message.AssistantMessage.of(List.of(tu1), Usage.EMPTY, "tool_use"),
                Message.AssistantMessage.of(List.of(tu2), Usage.EMPTY, "tool_use"),
                Message.AssistantMessage.of(List.of(new TextBlock("끝")), Usage.EMPTY, "end_turn"),
                Message.AssistantMessage.of(List.of(new TextBlock("추가 답")), Usage.EMPTY, "end_turn"))),
                "m", new ToolRegistry().register(new EchoTool()), "sys",
                new AgentServices(agent.permission.PermissionGate.allowAll(),
                        agent.hook.HookRunner.none(), ctxMgr, null, null));   // 비용 추적·영속성 없음

        List<AgentEvent> events = new ArrayList<>();
        try (var s = engine.submit("긴 작업 부탁해", CancellationToken.none())) {
            s.forEach(events::add);
        }

        // 루프 3회 반복 = 압축 실패 3회. 실패는 이력을 절대 건드리지 않는다 —
        // 치환(경계 마커)도 Compacted 이벤트도 없이 원래 질문이 그대로 남아 있어야 한다.
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(events).noneMatch(e -> e instanceof AgentEvent.Compacted);
        assertThat(engine.messages()).noneMatch(m -> m instanceof Message.NoticeMessage);
        assertThat(engine.messages()).anyMatch(m -> m instanceof Message.UserMessage u && !u.injected()
                && u.content().stream().anyMatch(b -> b instanceof TextBlock t
                        && t.text().equals("긴 작업 부탁해")));
        assertThat(events.get(events.size() - 1))
                .isEqualTo(new AgentEvent.TurnFinished(new Transition.Completed()));

        // 3연속 실패로 서킷 브레이커 트립 — 다음 턴부터는 요약 호출 자체가 안 나간다
        try (var s = engine.submit("한 번 더", CancellationToken.none())) {
            s.forEach(e -> {});
        }
        assertThat(attempts.get()).isEqualTo(3);
    }
}
