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

    public void newline() {
        terminal.writer().println();
        terminal.flush();
    }

    /** 스트리밍 청크: 개행 없이 즉시 흘린다(줄 단위 message()와 구분). */
    public void assistantChunk(String text) {
        terminal.writer().print(text);
        terminal.flush();
    }

    /** 내부 알림/오류 한 줄(노란색). 4장 리팩터링 때 빠졌던 헬퍼를 스트리밍 오류용으로 되살린다. */
    public void system(String text) {
        line(styled(text, AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW)));
    }

    /** 도구 실행 시작을 회색 한 줄로 표시한다(12장 — 미니 에이전트). */
    public void toolStarted(String name) {
        line(styled("  ⚙ " + name + " 실행…", AttributedStyle.DEFAULT.faint()));
    }

    /** 도구 실행 종료. 오류면 빨간색 ✗, 아니면 회색 ✓. */
    public void toolFinished(String name, boolean isError) {
        AttributedStyle style = isError
                ? AttributedStyle.DEFAULT.foreground(AttributedStyle.RED)
                : AttributedStyle.DEFAULT.faint();
        line(styled("  " + (isError ? "✗ " : "✓ ") + name, style));
    }

    private AttributedString styled(String text, AttributedStyle style) {
        return new AttributedString(text, style);
    }

    private void line(AttributedString s) {
        terminal.writer().println(s.toAnsi(terminal));
        terminal.flush();
    }
}
