package agent.exec;

import agent.message.ContentBlock.ToolResultBlock;
import agent.message.ContentBlock.ToolUseBlock;
import agent.message.Json;
import agent.tool.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolOrchestratorCancelTest {

    record In(String text) {}
    /** 실행되면 카운트를 올리는 안전 도구. 취소 단락이 실행을 막았는지 검증용. */
    static final class CountingEcho implements Tool<In, String> {
        final AtomicInteger calls = new AtomicInteger();
        public String name() { return "Echo"; }
        public String description() { return "echo"; }
        public Class<In> inputType() { return In.class; }
        public boolean isConcurrencySafe(In in) { return true; }
        public ToolResult<String> call(In in, ToolContext ctx) { calls.incrementAndGet(); return ToolResult.of(in.text()); }
        public ToolResultBlock mapResult(String out, String id) { return ToolResultBlock.ok(id, out); }
    }

    @Test
    void cancelled_context_yields_synthetic_error_results_for_all_uses() {
        var echo = new CountingEcho();
        var orch = new ToolOrchestrator(new ToolRegistry().register(echo));

        // 이미 취소된 토큰을 가진 컨텍스트
        var cancel = CancellationToken.root();
        cancel.cancel("interrupt");
        var ctx = ToolContext.of(Path.of(".")).withCancel(cancel);

        List<ToolUseBlock> uses = List.of(
                new ToolUseBlock("1", "Echo", Json.MAPPER.valueToTree(new In("A"))),
                new ToolUseBlock("2", "Echo", Json.MAPPER.valueToTree(new In("B"))),
                new ToolUseBlock("3", "Echo", Json.MAPPER.valueToTree(new In("C"))));

        List<ToolResultBlock> results = orch.runAll(uses, ctx, new ToolOrchestrator.Listener() {
            public void started(ToolUseBlock u) {}
            public void finished(ToolUseBlock u, boolean err) {}
        });

        // 모든 tool_use에 결과가 있고, 모두 오류(취소됨) — 고아 tool_use 없음
        assertThat(results).hasSize(3);
        assertThat(results).allSatisfy(r -> assertThat(r.isError()).isTrue());
        // 취소 단락이라 도구 본문은 한 번도 실행되지 않았다
        assertThat(echo.calls.get()).isZero();
    }
}
