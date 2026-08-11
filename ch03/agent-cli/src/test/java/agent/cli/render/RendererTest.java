package agent.cli.render;

import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
    void assistant_text_is_rendered_with_its_body() throws IOException {
        Harness h = newHarness();

        h.renderer().assistant("안녕 반가워");

        assertThat(captured(h.out())).contains("안녕 반가워");
    }

    @Test
    void user_and_system_render_their_text() throws IOException {
        Harness h = newHarness();

        h.renderer().user("이 파일 읽어줘");
        h.renderer().system("요청 실패: timeout");

        String all = captured(h.out());
        assertThat(all).contains("이 파일 읽어줘");
        assertThat(all).contains("요청 실패: timeout");
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
