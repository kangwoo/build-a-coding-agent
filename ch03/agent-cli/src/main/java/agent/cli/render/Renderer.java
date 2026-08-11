package agent.cli.render;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

public final class Renderer {
    private final Terminal terminal;

    public Renderer(Terminal terminal) {
        this.terminal = terminal;
    }

    public void banner() {
        line("터미널 코딩 에이전트  ·  /exit 로 종료", AttributedStyle.DEFAULT.faint());
    }

    public void goodbye() {
        line("안녕히 가세요 👋", AttributedStyle.DEFAULT.faint());
    }

    /** 시스템 알림(오류·안내). 노란색. */
    public void system(String text) {
        line(text, AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW));
    }

    /** 사용자가 친 줄을 (다시) 보여줄 때. 청록색. (이 장 루프에선 미사용 — LineReader가 입력을 에코한다. 4장 이후 Message 기반에서 쓰인다.) */
    public void user(String text) {
        line(text, AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN));
    }

    /** assistant의 응답 텍스트. 초록색. (3장은 OpenAI 응답 전체, 6장은 스트리밍 청크.) */
    public void assistant(String text) {
        line(text, AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN));
    }

    private void line(String text, AttributedStyle style) {
        terminal.writer().println(new AttributedString(text, style).toAnsi(terminal));
        terminal.flush();
    }
}
