package agent.tool.builtin;

import agent.tool.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EditToolTest {

    @TempDir Path dir;
    final ReadTool read = new ReadTool();
    final EditTool edit = new EditTool();

    @Test
    void edit_requires_prior_read() throws Exception {
        Path f = dir.resolve("a.txt");
        Files.writeString(f, "hello world");
        ToolContext ctx = ToolContext.of(dir);

        var input = new EditTool.Input(Path.of("a.txt"), "world", "java", false);
        // 읽지 않고 편집 → 거부
        assertThat(edit.validateInput(input, ctx)).isInstanceOf(ValidationResult.Fail.class);

        // 읽은 뒤 편집 → 통과
        read.call(new ReadTool.Input(Path.of("a.txt"), Optional.empty(), Optional.empty()), ctx);
        assertThat(edit.validateInput(input, ctx)).isInstanceOf(ValidationResult.Ok.class);

        edit.call(input, ctx);
        assertThat(Files.readString(f)).isEqualTo("hello java");
    }

    @Test
    void ambiguous_match_is_rejected() throws Exception {
        Path f = dir.resolve("b.txt");
        Files.writeString(f, "a a a");
        ToolContext ctx = ToolContext.of(dir);
        read.call(new ReadTool.Input(Path.of("b.txt"), Optional.empty(), Optional.empty()), ctx);

        var input = new EditTool.Input(Path.of("b.txt"), "a", "x", false);   // 3곳 매치, replaceAll=false
        assertThat(edit.validateInput(input, ctx))
                .isInstanceOfSatisfying(ValidationResult.Fail.class, f2 -> assertThat(f2.errorCode()).isEqualTo(9));
    }

    @Test
    void preserves_crlf_and_avoids_double_cr() throws Exception {
        Path f = dir.resolve("crlf.txt");
        Files.write(f, "a\r\nb\r\nc".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ToolContext ctx = ToolContext.of(dir);
        read.call(new ReadTool.Input(Path.of("crlf.txt"), Optional.empty(), Optional.empty()), ctx);

        edit.call(new EditTool.Input(Path.of("crlf.txt"), "b", "B\r\nB2", false), ctx);  // newString에 \r\n 섞임

        assertThat(Files.readString(f)).isEqualTo("a\r\nB\r\nB2\r\nc");   // CRLF 보존 + \r\r\n 없음
    }

    @Test
    void mtime_bump_without_content_change_is_not_stale() throws Exception {
        Path f = dir.resolve("d.txt");
        Files.writeString(f, "same\n");                              // 후행 개행 포함(보통의 소스 파일처럼)
        ToolContext ctx = ToolContext.of(dir);
        read.call(new ReadTool.Input(Path.of("d.txt"), Optional.empty(), Optional.empty()), ctx);

        // 내용은 그대로, mtime만 미래로(가짜 변경) — 내용 동일하므로 stale 아님
        long readTs = ctx.fileState().get(f).orElseThrow().timestampMs();
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(readTs + 5000));

        assertThat(edit.validateInput(new EditTool.Input(Path.of("d.txt"), "same", "changed", false), ctx))
                .isInstanceOf(ValidationResult.Ok.class);
    }
}
