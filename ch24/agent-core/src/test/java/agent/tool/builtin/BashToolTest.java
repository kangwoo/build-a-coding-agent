package agent.tool.builtin;

import agent.tool.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisabledOnOs(OS.WINDOWS)
class BashToolTest {

    static boolean bashAvailable() {
        try { return new ProcessBuilder("bash", "-c", "true").start().waitFor() == 0; }
        catch (Exception e) { return false; }
    }

    @TempDir Path dir;
    final BashTool tool = new BashTool();

    private BashTool.Input cmd(String c) { return new BashTool.Input(c, Optional.empty(), Optional.empty()); }

    @Test @EnabledIf("bashAvailable")
    void runs_command_and_captures_output() throws Exception {
        var out = tool.call(cmd("echo hello"), ToolContext.of(dir));
        assertThat(out.data().output().strip()).isEqualTo("hello");
        assertThat(out.data().exitCode()).isZero();
    }

    @Test @EnabledIf("bashAvailable")
    void merges_stderr_into_output() throws Exception {
        var out = tool.call(cmd("echo OUT; echo ERR 1>&2"), ToolContext.of(dir));
        assertThat(out.data().output()).contains("OUT").contains("ERR");   // merged fd
    }

    @Test @EnabledIf("bashAvailable")
    void times_out_long_command() throws Exception {
        var input = new BashTool.Input("sleep 5", Optional.of(200L), Optional.empty());
        var out = tool.call(input, ToolContext.of(dir));
        assertThat(out.data().timedOut()).isTrue();
    }

    @Test @EnabledIf("bashAvailable")
    void grep_no_match_is_not_an_error_in_mapped_result() throws Exception {
        // grep 무매치 → exit 1 이지만 오류가 아니어야 한다(mapResult가 명령별 해석 사용).
        // 파이프라인이 아니라 grep을 직접 첫 단어로 둔다(firstWord 해석의 한계 — 15.3 참고).
        var out = tool.call(cmd("grep zzzznomatch /dev/null"), ToolContext.of(dir));
        assertThat(out.data().exitCode()).isEqualTo(1);
        var block = tool.mapResult(out.data(), "tu_1");
        assertThat(block.isError()).isFalse();
    }

    @Test
    void exit_code_semantics() {
        assertThat(BashTool.isError("grep x file", 1)).isFalse();   // 무매치는 오류 아님
        assertThat(BashTool.isError("grep x file", 2)).isTrue();    // exit 2+는 진짜 오류
        assertThat(BashTool.isError("gcc main.c", 1)).isTrue();     // 컴파일 실패는 오류
        assertThat(BashTool.isError("ls", 0)).isFalse();
    }
}
