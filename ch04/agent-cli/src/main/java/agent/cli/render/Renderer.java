package agent.cli.render;

import agent.message.ContentBlock;
import agent.message.Message;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

public final class Renderer {
    private final Terminal terminal;

    public Renderer(Terminal terminal) {
        this.terminal = terminal;
    }

    public void banner() {
        line(styled("터미널 코딩 에이전트  ·  /exit 로 종료", AttributedStyle.DEFAULT.faint()));
    }

    public void goodbye() {
        line(styled("안녕히 가세요 👋", AttributedStyle.DEFAULT.faint()));
    }

    /** 역할에 맞는 색으로 메시지 한 건을 그린다(3장의 user/assistant(String)를 대체). */
    public void message(Message m) {
        AttributedStyle style = switch (m) {
            case Message.UserMessage u      -> AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
            case Message.AssistantMessage a -> AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
            case Message.NoticeMessage s    -> AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
        };
        line(styled(plainText(m), style));
    }

    /** Message에서 표시용 텍스트만 뽑는다(블록 중 text만; tool_use 등은 12장에서). */
    public static String plainText(Message m) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : m.content()) {
            if (b instanceof ContentBlock.TextBlock t) sb.append(t.text());
        }
        return sb.toString();
    }

    private AttributedString styled(String text, AttributedStyle style) {
        return new AttributedString(text, style);
    }

    private void line(AttributedString s) {
        terminal.writer().println(s.toAnsi(terminal));
        terminal.flush();
    }
}
