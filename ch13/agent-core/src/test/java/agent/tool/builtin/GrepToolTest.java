package agent.tool.builtin;

import agent.tool.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GrepToolTest {

    static boolean ripgrepAvailable() {
        try { return new ProcessBuilder("rg", "--version").start().waitFor() == 0; }
        catch (Exception e) { return false; }
    }

    @TempDir Path dir;

    @Test @EnabledIf("ripgrepAvailable")
    void finds_matching_lines() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello\nTODO: fix\nbye");
        Files.writeString(dir.resolve("b.txt"), "nothing here");

        var tool = new GrepTool();
        var input = new GrepTool.Input("TODO", Optional.empty(), Optional.empty(),
                Optional.of(GrepTool.OutputMode.CONTENT), false);
        var result = tool.call(input, ToolContext.of(dir));

        assertThat(result.data().lines()).anyMatch(l -> l.contains("TODO: fix"));
    }

    @Test @EnabledIf("ripgrepAvailable")
    void no_match_is_not_an_error() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello");
        var tool = new GrepTool();
        var input = new GrepTool.Input("ZZZ", Optional.empty(), Optional.empty(),
                Optional.empty(), false);
        var result = tool.call(input, ToolContext.of(dir));   // 예외 아님

        assertThat(result.data().lines()).isEmpty();          // 무매치 = 빈 결과
    }

    @Test @EnabledIf("ripgrepAvailable")
    void pattern_starting_with_dash_is_literal() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "see --xyz here");
        var tool = new GrepTool();
        // '--' 구분자 덕분에 '--xyz'를 rg 플래그로 오인하지 않는다(없으면 exit 2 → 예외).
        var input = new GrepTool.Input("--xyz", Optional.empty(), Optional.empty(),
                Optional.of(GrepTool.OutputMode.CONTENT), false);
        var result = tool.call(input, ToolContext.of(dir));

        assertThat(result.data().lines()).anyMatch(l -> l.contains("--xyz"));
    }
}
