package agent.tool.builtin;

import agent.message.ContentBlock;
import agent.tool.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadToolTest {

    @TempDir Path dir;
    final ReadTool tool = new ReadTool();

    @Test
    void reads_with_line_numbers_and_records_state() throws Exception {
        Path f = dir.resolve("hello.txt");
        Files.writeString(f, "first\nsecond\nthird");
        ToolContext ctx = ToolContext.of(dir);

        var input = new ReadTool.Input(Path.of("hello.txt"), Optional.empty(), Optional.empty());
        var result = tool.call(input, ctx);

        var text = (ReadResult.TextResult) result.data();
        assertThat(text.totalLines()).isEqualTo(3);

        String rendered = ((ContentBlock.TextBlock)
                tool.mapResult(result.data(), "tu").content().get(0)).text();
        assertThat(rendered).contains("     1\tfirst");   // 라인번호 + 탭 + 내용
        assertThat(rendered).contains("     3\tthird");

        // 읽었음이 캐시에 기록됨 (10장 안전장치의 토대)
        assertThat(ctx.fileState().get(dir.resolve("hello.txt"))).isPresent();
    }

    @Test
    void second_read_same_range_returns_file_unchanged() throws Exception {
        Path f = dir.resolve("a.txt");
        Files.writeString(f, "x");
        ToolContext ctx = ToolContext.of(dir);
        var input = new ReadTool.Input(Path.of("a.txt"), Optional.empty(), Optional.empty());

        tool.call(input, ctx);                                   // 1차: 실제 읽기
        var second = tool.call(input, ctx);                      // 2차: 변경 없음

        assertThat(second.data()).isInstanceOf(ReadResult.FileUnchanged.class);
    }

    @Test
    void missing_file_fails_validation() {
        var input = new ReadTool.Input(Path.of("nope.txt"), Optional.empty(), Optional.empty());
        assertThat(tool.validateInput(input, ToolContext.of(dir)))
                .isInstanceOf(ValidationResult.Fail.class);
    }

    @Test
    void binary_file_yields_clean_error_not_crash() throws Exception {
        Path f = dir.resolve("bin.dat");
        Files.write(f, new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, (byte) 0x80});
        var input = new ReadTool.Input(Path.of("bin.dat"), Optional.empty(), Optional.empty());
        assertThatThrownBy(() -> tool.call(input, ToolContext.of(dir)))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void oversized_image_is_rejected() throws Exception {
        Path f = dir.resolve("big.png");
        Files.write(f, new byte[5 * 1024 * 1024 + 1]);           // MAX_IMAGE_BYTES(5MB) 초과
        var input = new ReadTool.Input(Path.of("big.png"), Optional.empty(), Optional.empty());
        assertThatThrownBy(() -> tool.call(input, ToolContext.of(dir)))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("이미지가 큽니다");
    }

    @Test
    void degenerate_limit_does_not_crash() throws Exception {
        Files.writeString(dir.resolve("c.txt"), "a\nb\nc");
        // validateInput이 먼저 막지만, call도 subList 예외 없이 빈 결과를 내야 한다.
        var input = new ReadTool.Input(Path.of("c.txt"), Optional.of(2), Optional.of(-1));
        var result = tool.call(input, ToolContext.of(dir));
        assertThat(((ReadResult.TextResult) result.data()).content()).isEmpty();
    }

    @Test
    void deserializes_optional_offset_from_json() {
        // §9.2의 목적: Optional<Integer>가 JSON에서 역직렬화되는가(jdk8 모듈)
        var node = agent.message.Json.MAPPER.createObjectNode();
        node.put("filePath", "x.txt").put("offset", 5);            // limit 생략
        var in = agent.message.Json.read(node.toString(), ReadTool.Input.class);
        assertThat(in.offset()).contains(5);
        assertThat(in.limit()).isEmpty();
    }
}
