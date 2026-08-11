package agent.cli.render;

import agent.message.ContentBlock;
import agent.message.Json;
import agent.message.Message;
import agent.message.Usage;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RendererTest {

    private record Harness(Renderer renderer, ByteArrayOutputStream out) {}

    /** 출력을 가로채는 더미 터미널 + Renderer. */
    private Harness newHarness() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // DumbTerminal: PTY 없이 출력을 out으로 동기 캡처(헤드리스·멀티바이트 안전).
        Terminal terminal = new DumbTerminal(
                "test", "dumb",
                new ByteArrayInputStream(new byte[0]),   // 입력은 안 씀
                out,
                StandardCharsets.UTF_8);
        return new Harness(new Renderer(terminal), out);
    }

    private String captured(ByteArrayOutputStream out) {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void plainText_concatenates_text_blocks_and_ignores_non_text() {
        var toolUse = new ContentBlock.ToolUseBlock(
                "tu_1", "Read", Json.MAPPER.createObjectNode().put("path", "/tmp/a.txt"));
        Message m = Message.AssistantMessage.of(
                List.of(new ContentBlock.TextBlock("읽어"),
                        toolUse,                                  // text가 아닌 블록은 건너뛴다
                        new ContentBlock.TextBlock("볼게요")),
                Usage.EMPTY, "tool_use");

        assertThat(Renderer.plainText(m)).isEqualTo("읽어볼게요");
    }

    @Test
    void message_renders_text_for_each_role() throws IOException {
        Harness h = newHarness();

        h.renderer().message(Message.UserMessage.of("이 파일 읽어줘"));
        h.renderer().message(Message.AssistantMessage.of(
                List.of(new ContentBlock.TextBlock("네, 읽어볼게요")), Usage.EMPTY, "stop"));
        h.renderer().message(Message.NoticeMessage.of("호출 실패: timeout"));

        String all = captured(h.out());
        assertThat(all).contains("이 파일 읽어줘");   // user
        assertThat(all).contains("네, 읽어볼게요");     // assistant
        assertThat(all).contains("호출 실패: timeout"); // system(내부 알림)
    }

    @Test
    void banner_and_goodbye_emit_fixed_strings() throws IOException {
        Harness h = newHarness();

        h.renderer().banner();
        h.renderer().goodbye();

        String all = captured(h.out());
        assertThat(all).contains("터미널 코딩 에이전트");
        assertThat(all).contains("안녕히 가세요");
    }
}
