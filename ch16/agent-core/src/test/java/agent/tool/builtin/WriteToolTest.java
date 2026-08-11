package agent.tool.builtin;

import agent.tool.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WriteToolTest {

    @TempDir Path dir;
    final ReadTool read = new ReadTool();
    final WriteTool write = new WriteTool();
    final EditTool edit = new EditTool();

    @Test
    void creates_new_file_in_nested_dir() throws Exception {
        ToolContext ctx = ToolContext.of(dir);
        var input = new WriteTool.Input(Path.of("sub/new.txt"), "hello\nworld\n");
        assertThat(write.validateInput(input, ctx)).isInstanceOf(ValidationResult.Ok.class);

        var r = write.call(input, ctx).data();
        assertThat(r.created()).isTrue();
        assertThat(Files.readString(dir.resolve("sub/new.txt"))).isEqualTo("hello\nworld\n");
    }

    @Test
    void overwrite_requires_prior_read() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "old");
        ToolContext ctx = ToolContext.of(dir);
        var input = new WriteTool.Input(Path.of("a.txt"), "new");

        assertThat(write.validateInput(input, ctx))           // 읽지 않고 덮어쓰기 → 거부
                .isInstanceOfSatisfying(ValidationResult.Fail.class, f -> assertThat(f.errorCode()).isEqualTo(2));

        read.call(new ReadTool.Input(Path.of("a.txt"), Optional.empty(), Optional.empty()), ctx);
        assertThat(write.validateInput(input, ctx)).isInstanceOf(ValidationResult.Ok.class);   // 읽은 뒤 → 통과
    }

    @Test
    void external_change_after_read_is_rejected() throws Exception {
        Path f = dir.resolve("c.txt");
        Files.writeString(f, "v1");
        ToolContext ctx = ToolContext.of(dir);
        read.call(new ReadTool.Input(Path.of("c.txt"), Optional.empty(), Optional.empty()), ctx);

        long readTs = ctx.fileState().get(f).orElseThrow().timestampMs();
        Files.writeString(f, "v2 — 외부 변경");                // 내용도 mtime도 바뀜
        Files.setLastModifiedTime(f, FileTime.fromMillis(readTs + 5000));

        assertThat(write.validateInput(new WriteTool.Input(Path.of("c.txt"), "v3"), ctx))
                .isInstanceOfSatisfying(ValidationResult.Fail.class, f2 -> assertThat(f2.errorCode()).isEqualTo(3));
    }

    @Test
    void write_then_edit_without_reread_passes_read_before_write() throws Exception {
        ToolContext ctx = ToolContext.of(dir);
        write.call(new WriteTool.Input(Path.of("b.txt"), "alpha"), ctx);   // Write가 캐시 갱신

        var e = new EditTool.Input(Path.of("b.txt"), "alpha", "beta", false);
        assertThat(edit.validateInput(e, ctx)).isInstanceOf(ValidationResult.Ok.class);
        edit.call(e, ctx);
        assertThat(Files.readString(dir.resolve("b.txt"))).isEqualTo("beta");
    }
}
