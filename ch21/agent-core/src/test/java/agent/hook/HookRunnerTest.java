package agent.hook;

import agent.message.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisabledOnOs(OS.WINDOWS)
class HookRunnerTest {

    static boolean bash() {
        try { return new ProcessBuilder("bash", "-c", "true").start().waitFor() == 0; }
        catch (Exception e) { return false; }
    }

    @TempDir Path dir;

    @Test @EnabledIf("bash")
    void exit_2_blocks_the_tool() {
        var hook = new HookCommand.Command("echo blocked 1>&2; exit 2", 10);
        var config = Map.of(HookEvent.PRE_TOOL_USE,
                List.of(new HookMatcher("Bash", List.of(hook))));
        var runner = new HookRunner(config, dir, () -> true);

        var decision = runner.runPreToolUse("Bash", Json.MAPPER.createObjectNode().put("command", "ls"));
        assertThat(decision.denied()).isTrue();               // exit 2 → 차단
        assertThat(decision.message()).contains("blocked");   // stderr가 모델 피드백으로 전달
    }

    @Test @EnabledIf("bash")
    void untrusted_workspace_skips_hooks() {
        var hook = new HookCommand.Command("exit 2", 10);
        var config = Map.of(HookEvent.PRE_TOOL_USE,
                List.of(new HookMatcher("*", List.of(hook))));
        var runner = new HookRunner(config, dir, () -> false);   // 신뢰 안 함

        // 신뢰하지 않으면 훅을 아예 안 돌림 → 차단되지 않음
        assertThat(runner.runPreToolUse("Bash",
                Json.MAPPER.createObjectNode().put("command", "ls")).denied()).isFalse();
    }

    @Test @EnabledIf("bash")
    void json_output_carries_additional_context() {
        // exit 0 + JSON {additionalContext:"..."} → 차단 아님 + 컨텍스트 전달
        var hook = new HookCommand.Command(
                "echo '{\"additionalContext\":\"리포 규칙: 줄임말 금지\"}'", 10);
        var config = Map.of(HookEvent.PRE_TOOL_USE,
                List.of(new HookMatcher("Write", List.of(hook))));
        var runner = new HookRunner(config, dir, () -> true);

        var d = runner.runPreToolUse("Write", Json.MAPPER.createObjectNode());
        assertThat(d.denied()).isFalse();
        assertThat(d.addedContext()).anySatisfy(c -> assertThat(c).contains("줄임말 금지"));
    }

    @Test @EnabledIf("bash")
    void non_matching_tool_is_not_hooked() {
        var hook = new HookCommand.Command("exit 2", 10);
        var config = Map.of(HookEvent.PRE_TOOL_USE,
                List.of(new HookMatcher("Bash", List.of(hook))));   // Bash에만
        var runner = new HookRunner(config, dir, () -> true);
        // Read는 매처에 안 걸리므로 차단되지 않는다
        assertThat(runner.runPreToolUse("Read", Json.MAPPER.createObjectNode()).denied()).isFalse();
    }

    @Test
    void matcher_three_meanings() {
        assertThat(new HookMatcher("*", List.of()).matches("Bash")).isTrue();      // 전체
        assertThat(new HookMatcher("Write|Edit", List.of()).matches("Edit")).isTrue(); // 파이프 정확매치
        assertThat(new HookMatcher("Write|Edit", List.of()).matches("Read")).isFalse();
        assertThat(new HookMatcher(".*Tool", List.of()).matches("BashTool")).isTrue(); // 정규식
    }
}
