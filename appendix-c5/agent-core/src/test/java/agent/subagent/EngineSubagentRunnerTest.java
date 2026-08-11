package agent.subagent;

import agent.engine.AgentEvent;
import agent.exec.CancellationToken;
import agent.llm.*;
import agent.message.*;
import agent.message.ContentBlock.*;
import agent.tool.ToolRegistry;
import agent.tool.builtin.EchoTool;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class EngineSubagentRunnerTest {

    /** 스크립트된 assistant 메시지들을 차례로 내놓는 가짜 모델(12장 AgentEngineTest 패턴 재사용). */
    static final class FakeModel implements LlmClient {
        private final Deque<Message.AssistantMessage> script;
        FakeModel(List<Message.AssistantMessage> turns) { this.script = new ArrayDeque<>(turns); }

        @Override public EventStream<StreamEvent> stream(LlmRequest req, CancellationToken c) {
            Message.AssistantMessage turn = script.poll();
            return EventStreams.fromProducer(sink -> emit(sink, turn));
        }
        @Override public Message.AssistantMessage create(LlmRequest req, CancellationToken c) { return script.poll(); }
        @Override public LlmCapabilities capabilities() { return new LlmCapabilities(false, false, false, false); }
        @Override public String name() { return "fake"; }

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

    /** [진행 텍스트+도구 호출 턴 → 최종 텍스트 턴]을 스크립트한다 — 최종 턴의 텍스트만 반환돼야 한다. */
    private static List<Message.AssistantMessage> toolThenText(String finalText) {
        var toolUse = new ToolUseBlock("tu_1", "Echo",
                Json.MAPPER.createObjectNode().put("text", "PONG"));
        var turn1 = Message.AssistantMessage.of(                     // 도구 턴에도 진행 텍스트가 흐른다
                List.of(new TextBlock("파일을 살펴보는 중…"), toolUse), Usage.EMPTY, "tool_use");
        var turn2 = Message.AssistantMessage.of(
                List.of(new TextBlock(finalText)), Usage.EMPTY, "end_turn");
        return List.of(turn1, turn2);
    }

    @Test
    void runs_tool_then_collects_only_final_text() {
        var model = new FakeModel(toolThenText("  조사 완료: 파일 3개  "));   // 양끝 공백 → strip 확인
        var runner = new EngineSubagentRunner(model, "m",
                def -> new ToolRegistry().register(new EchoTool()));

        String result = runner.run(AgentDefinition.GENERAL, "src/ 조사", CancellationToken.none());

        // 최종 텍스트만 모이고 strip된다. 중간 턴의 진행 텍스트/도구 호출은 결과에 섞이지 않는다.
        assertThat(result).isEqualTo("조사 완료: 파일 3개");
        assertThat(result).doesNotContain("PONG").doesNotContain("살펴보는 중");
    }

    @Test
    void each_run_is_isolated_with_a_fresh_engine() {
        // 격리 불변식: run()마다 새 AgentEngine(= 새 메시지 배열)을 만든다.
        // 같은 러너로 두 번 호출해도, 두 번째 호출이 첫 번째의 대화를 이어받지 않는다.
        // FakeModel은 호출당 한 턴씩 스크립트를 소비하므로, run()이 엔진을 재사용했다면
        // 두 번째 run은 스크립트가 비어(null) NPE를 내거나 결과가 섞일 것이다.
        var script = new ArrayList<Message.AssistantMessage>();
        script.addAll(toolThenText("first 완료"));    // 1번째 run이 소비할 두 턴
        script.addAll(toolThenText("second 완료"));   // 2번째 run이 소비할 두 턴
        var model = new FakeModel(script);
        var runner = new EngineSubagentRunner(model, "m",
                def -> new ToolRegistry().register(new EchoTool()));

        // 첫 run은 두 턴(도구→텍스트)을 소비하고 "first 완료"로 끝난다.
        assertThat(runner.run(AgentDefinition.GENERAL, "a", CancellationToken.none()))
                .isEqualTo("first 완료");
        // 두 번째 run은 새 엔진에서 다음 두 턴을 소비한다. 첫 대화가 새지 않았다.
        assertThat(runner.run(AgentDefinition.GENERAL, "b", CancellationToken.none()))
                .isEqualTo("second 완료");
    }

    @Test
    void answers_without_tools_when_model_replies_directly() {
        // 모델이 도구 없이 바로 답하면 그 텍스트가 곧 결과.
        var turn = Message.AssistantMessage.of(
                List.of(new TextBlock("바로 답함")), Usage.EMPTY, "end_turn");
        var model = new FakeModel(List.of(turn));
        var runner = new EngineSubagentRunner(model, "m", def -> new ToolRegistry());

        String result = runner.run(AgentDefinition.GENERAL, "질문", CancellationToken.none());
        assertThat(result).isEqualTo("바로 답함");
    }

    @Test
    void interrupt_preserves_partial_text_with_truncation_marker() throws Exception {
        // 끝나지 않는 스트림: 델타 하나를 흘린 뒤 MessageStop 없이 멈춘다(gate는 열리지 않는다 —
        // run()의 close()가 생산자를 인터럽트해 깨운다). 그 위에서 소비 스레드 인터럽트를 연출한다.
        var deltaEmitted = new CountDownLatch(1);
        var gate = new CountDownLatch(1);
        LlmClient hanging = new LlmClient() {
            @Override public EventStream<StreamEvent> stream(LlmRequest req, CancellationToken c) {
                return EventStreams.fromProducer(sink -> {
                    sink.emit(new StreamEvent.MessageStart(Usage.EMPTY));
                    sink.emit(new StreamEvent.BlockStart(0, new TextBlock("")));
                    sink.emit(new StreamEvent.BlockDelta(0, new StreamEvent.TextDelta("부분 텍스트")));
                    deltaEmitted.countDown();
                    gate.await();
                });
            }
            @Override public Message.AssistantMessage create(LlmRequest req, CancellationToken c) { return null; }
            @Override public LlmCapabilities capabilities() { return new LlmCapabilities(false, false, false, false); }
            @Override public String name() { return "hanging"; }
        };
        var runner = new EngineSubagentRunner(hanging, "m", def -> new ToolRegistry());

        var result = new AtomicReference<String>();
        Thread consumer = new Thread(() -> result.set(
                runner.run(AgentDefinition.GENERAL, "질문", CancellationToken.none())));
        consumer.start();

        deltaEmitted.await();                 // 델타가 파이프라인에 들어간 뒤,
        awaitStableWaiting(consumer);         // 소비자가 그 델타를 소비하고 다음 이벤트 대기로 파킹될 때까지
        consumer.interrupt();                 // 소비 스레드 인터럽트 연출(14장 Ctrl+C의 축소판)
        consumer.join(5_000);

        // 완결로 위장하지 않고(잘림 표시), 이미 지불한 토큰(부분 텍스트)은 버리지 않는다.
        assertThat(consumer.isAlive()).isFalse();
        assertThat(result.get()).startsWith("[중단됨 — 아래는 부분 결과]");
        assertThat(result.get()).contains("부분 텍스트");
    }

    /** 스레드가 큐 대기(WAITING)로 안정될 때까지 — 흘러든 델타를 다 소비하고 파킹됐다는 뜻. */
    private static void awaitStableWaiting(Thread t) throws InterruptedException {
        int stable = 0;
        while (stable < 3) {
            stable = t.getState() == Thread.State.WAITING ? stable + 1 : 0;
            Thread.sleep(10);
        }
    }

    @Test
    void uses_definition_tool_pool() {
        // 격리 검증: toolPool은 def별로 호출된다. AssistantTextDelta만 누적되는지 재확인.
        var model = new FakeModel(toolThenText("done"));
        boolean[] poolCalled = {false};
        var runner = new EngineSubagentRunner(model, "m", def -> {
            poolCalled[0] = true;
            return new ToolRegistry().register(new EchoTool());
        });

        runner.run(AgentDefinition.GENERAL, "x", CancellationToken.none());
        assertThat(poolCalled[0]).isTrue();
    }
}
