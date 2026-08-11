package agent.exec;

import agent.message.ContentBlock.ToolResultBlock;
import agent.message.ContentBlock.ToolUseBlock;
import agent.message.ContentBlock.TextBlock;
import agent.message.Json;
import agent.tool.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolOrchestratorTest {

    /** 일부러 느린 안전 도구. 끝나는 순서를 뒤섞어 순서 보존을 검증한다. */
    record SlowEchoInput(int delayMs, String text) {}
    static final class SlowEcho implements Tool<SlowEchoInput, String> {
        private final AtomicInteger live;   // 동시에 살아있는 호출 수 추적(테스트용)
        private final AtomicInteger peak;
        SlowEcho() { this(new AtomicInteger(), new AtomicInteger()); }
        SlowEcho(AtomicInteger live, AtomicInteger peak) { this.live = live; this.peak = peak; }
        public String name() { return "SlowEcho"; }
        public String description() { return "delay 후 text 반환"; }
        public Class<SlowEchoInput> inputType() { return SlowEchoInput.class; }
        public boolean isConcurrencySafe(SlowEchoInput in) { return true; }
        public ToolResult<String> call(SlowEchoInput in, ToolContext ctx) throws Exception {
            int now = live.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try { Thread.sleep(in.delayMs()); }
            finally { live.decrementAndGet(); }
            return ToolResult.of(in.text());
        }
        public ToolResultBlock mapResult(String out, String id) { return ToolResultBlock.ok(id, out); }
    }

    /** 위험(직렬) 도구 — 동시에 돌면 안 된다. 겹침이 관측되면 실패한다. */
    static final class UnsafeMark implements Tool<SlowEchoInput, String> {
        private final AtomicInteger live;
        UnsafeMark(AtomicInteger live) { this.live = live; }
        public String name() { return "Unsafe"; }
        public String description() { return "직렬 전용"; }
        public Class<SlowEchoInput> inputType() { return SlowEchoInput.class; }
        // isConcurrencySafe 기본값(false) 사용 — 직렬로 가야 한다
        public ToolResult<String> call(SlowEchoInput in, ToolContext ctx) throws Exception {
            int now = live.incrementAndGet();
            try {
                if (now > 1) throw new IllegalStateException("위험 도구가 병렬로 실행됨!");
                Thread.sleep(in.delayMs());
            } finally { live.decrementAndGet(); }
            return ToolResult.of(in.text());
        }
        public ToolResultBlock mapResult(String out, String id) { return ToolResultBlock.ok(id, out); }
    }

    private ToolUseBlock use(String id, int delay, String text) {
        return new ToolUseBlock(id, "SlowEcho",
                Json.MAPPER.valueToTree(new SlowEchoInput(delay, text)));
    }
    private ToolContext here() { return ToolContext.of(java.nio.file.Path.of(".")); }
    private static final ToolOrchestrator.Listener NOOP = new ToolOrchestrator.Listener() {
        public void started(ToolUseBlock u) {}
        public void finished(ToolUseBlock u, boolean err) {}
    };

    @Test
    void parallel_results_preserve_call_order() {
        var orch = new ToolOrchestrator(new ToolRegistry().register(new SlowEcho()));
        // A는 느리고 C는 빠르다 → 끝나는 순서는 C,B,A 이지만 결과는 A,B,C 여야 한다
        List<ToolUseBlock> uses = List.of(
                use("a", 120, "A"), use("b", 60, "B"), use("c", 10, "C"));

        var finishOrder = new ConcurrentLinkedQueue<String>();
        List<ToolResultBlock> results = orch.runAll(uses, here(),
                new ToolOrchestrator.Listener() {
                    public void started(ToolUseBlock u) {}
                    public void finished(ToolUseBlock u, boolean err) { finishOrder.add(u.id()); }
                });

        // 결과는 호출 순서대로
        assertThat(results).extracting(r -> ((TextBlock) r.content().get(0)).text())
                .containsExactly("A", "B", "C");
        // 실제로 병렬이었음(빠른 C가 먼저 끝남)
        assertThat(finishOrder.peek()).isEqualTo("c");
    }

    @Test
    void unknown_tool_still_pairs_an_error_result() {
        var orch = new ToolOrchestrator(new ToolRegistry());   // 빈 레지스트리
        var bad = new ToolUseBlock("x", "NoSuchTool", Json.MAPPER.createObjectNode());
        var finished = new ArrayList<Boolean>();
        List<ToolResultBlock> results = orch.runAll(List.of(bad), here(),
                new ToolOrchestrator.Listener() {
                    public void started(ToolUseBlock u) {}
                    public void finished(ToolUseBlock u, boolean err) { finished.add(err); }
                });
        assertThat(results).hasSize(1);
        assertThat(results.get(0).isError()).isTrue();
        assertThat(finished).containsExactly(true);
    }

    @Test
    void max_concurrency_is_respected() {
        var live = new AtomicInteger(); var peak = new AtomicInteger();
        var orch = new ToolOrchestrator(
                new ToolRegistry().register(new SlowEcho(live, peak)), 1);   // 상한 1 = 직렬화
        List<ToolUseBlock> uses = List.of(use("a", 40, "A"), use("b", 40, "B"), use("c", 40, "C"));
        orch.runAll(uses, here(), NOOP);
        assertThat(peak.get()).isEqualTo(1);   // 동시 실행이 1을 넘지 않았다
    }

    @Test
    void unsafe_tool_runs_alone_between_parallel_batches() {
        var safeLive = new AtomicInteger(); var safePeak = new AtomicInteger();
        var unsafeLive = new AtomicInteger();
        var orch = new ToolOrchestrator(new ToolRegistry()
                .register(new SlowEcho(safeLive, safePeak))
                .register(new UnsafeMark(unsafeLive)));
        // Safe, Safe, Unsafe, Safe  → [batch:Safe,Safe] [Unsafe 단독] [Safe]
        List<ToolUseBlock> uses = List.of(
                use("a", 30, "A"), use("b", 30, "B"),
                new ToolUseBlock("u", "Unsafe", Json.MAPPER.valueToTree(new SlowEchoInput(10, "U"))),
                use("d", 30, "D"));
        List<ToolResultBlock> results = orch.runAll(uses, here(), NOOP);

        // 결과 순서 보존, 위험 도구는 예외 없이(병렬로 안 돌아서) 성공
        assertThat(results).extracting(r -> ((TextBlock) r.content().get(0)).text())
                .containsExactly("A", "B", "U", "D");
        assertThat(results).allSatisfy(r -> assertThat(r.isError()).isFalse());
        assertThat(safePeak.get()).isGreaterThanOrEqualTo(2);   // 처음 두 Safe는 실제로 겹쳤다
    }
}
