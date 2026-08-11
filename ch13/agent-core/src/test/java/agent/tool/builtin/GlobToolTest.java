package agent.tool.builtin;

import agent.tool.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GlobToolTest {

    static boolean ripgrepAvailable() {
        try { return new ProcessBuilder("rg", "--version").start().waitFor() == 0; }
        catch (Exception e) { return false; }
    }

    @TempDir Path dir;

    @Test @EnabledIf("ripgrepAvailable")
    void finds_files_by_glob_relativized() throws Exception {
        Files.writeString(dir.resolve("Foo.java"), "class Foo {}");
        Files.writeString(dir.resolve("readme.md"), "# hi");
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/Bar.java"), "class Bar {}");

        var tool = new GlobTool();
        var result = tool.call(new GlobTool.Input("**/*.java", Optional.empty()), ToolContext.of(dir));

        assertThat(result.data().files()).contains("Foo.java", "sub/Bar.java");   // 상대경로
        assertThat(result.data().files()).noneMatch(f -> f.endsWith(".md"));      // glob 필터
    }
}
