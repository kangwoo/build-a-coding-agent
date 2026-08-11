package agent.tool;

import agent.message.ContentBlock;
import agent.message.ContentBlock.ToolResultBlock;
import agent.message.ContentBlock.ToolUseBlock;
import agent.message.Json;
import agent.tool.builtin.EchoTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutorTest {

    private ToolResultBlock run(Tool<?, ?> tool, JsonNode input) {
        var registry = new ToolRegistry().register(tool);
        var use = new ToolUseBlock("tu_1", tool.name(), input);
        var found = registry.find(use.name()).orElseThrow();
        return ToolExecutor.runToolUse(found, use, ToolContext.of(Path.of(".")));
    }

    @Test
    void executes_and_returns_tool_result() {
        ObjectNode in = Json.MAPPER.createObjectNode().put("text", "안녕");
        ToolResultBlock out = run(new EchoTool(), in);

        assertThat(out.isError()).isFalse();
        assertThat(out.toolUseId()).isEqualTo("tu_1");                 // tool_use와 짝이 맞음
        assertThat(((ContentBlock.TextBlock) out.content().get(0)).text()).isEqualTo("안녕");
    }

    @Test
    void malformed_input_becomes_error_result_not_exception() {
        ObjectNode in = Json.MAPPER.createObjectNode().put("wrong_field", 123);
        ToolResultBlock out = run(new EchoTool(), in);

        // 예외가 아니라 is_error tool_result로 표면화 (불변식: 모든 tool_use에 tool_result)
        assertThat(out.isError()).isTrue();
        assertThat(out.toolUseId()).isEqualTo("tu_1");
    }

    @Test
    void null_input_becomes_error_result_not_exception() {
        // JSON null은 역직렬화를 통과해 자바 null이 된다. 가드가 없으면 도구에서 NPE가 샌다.
        ToolResultBlock out = run(new FlakyTool(Fault.NONE), Json.MAPPER.nullNode());

        assertThat(out.isError()).isTrue();
        assertThat(text(out)).contains("<tool_use_error>");
    }

    @Test
    void validation_failure_becomes_error_result() {
        ToolResultBlock out = run(new FlakyTool(Fault.VALIDATE), input("x"));

        assertThat(out.isError()).isTrue();
        assertThat(text(out)).contains("<tool_use_error>").contains("나쁜 입력");
    }

    @Test
    void permission_deny_becomes_error_result() {
        ToolResultBlock out = run(new FlakyTool(Fault.DENY), input("x"));

        assertThat(out.isError()).isTrue();
        assertThat(text(out)).contains("거부됨");
    }

    @Test
    void call_exception_becomes_error_result_not_exception() {
        ToolResultBlock out = run(new FlakyTool(Fault.THROW), input("x"));

        assertThat(out.isError()).isTrue();
        assertThat(text(out)).contains("폭발");
    }

    // ── 헬퍼 ─────────────────────────────────────────────
    private static ObjectNode input(String text) {
        return Json.MAPPER.createObjectNode().put("text", text);
    }

    private static String text(ToolResultBlock b) {
        return ((ContentBlock.TextBlock) b.content().get(0)).text();
    }

    /** 검증 실패·권한 거부·실행 예외를 선택적으로 일으키는 테스트용 도구. */
    private enum Fault { NONE, VALIDATE, DENY, THROW }

    private record FlakyTool(Fault fault) implements Tool<FlakyTool.Input, String> {
        record Input(String text) {}

        @Override public String name() { return "Flaky"; }
        @Override public String description() { return "테스트용 도구"; }
        @Override public Class<Input> inputType() { return Input.class; }

        @Override public ValidationResult validateInput(Input in, ToolContext ctx) {
            if (in.text().isEmpty()) return ValidationResult.fail("빈 입력", 1);   // null이면 여기서 NPE날 자리
            return fault == Fault.VALIDATE ? ValidationResult.fail("나쁜 입력", 7) : ValidationResult.ok();
        }

        @Override public PermissionResult checkPermissions(Input in, ToolContext ctx) {
            return fault == Fault.DENY ? PermissionResult.deny("거부됨") : PermissionResult.allow();
        }

        @Override public ToolResult<String> call(Input in, ToolContext ctx) {
            if (fault == Fault.THROW) throw new RuntimeException("폭발");
            return ToolResult.of(in.text());
        }

        @Override public ToolResultBlock mapResult(String output, String toolUseId) {
            return ToolResultBlock.ok(toolUseId, output);
        }
    }
}
