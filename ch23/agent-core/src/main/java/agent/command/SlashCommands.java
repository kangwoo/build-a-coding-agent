package agent.command;

import java.util.Optional;

public final class SlashCommands {
    private SlashCommands() {}

    public record Parsed(String name, String args) {}

    /** "/cost foo bar" → (cost, "foo bar"). 슬래시 커맨드가 아니면 empty. */
    public static Optional<Parsed> parse(String input) {
        String s = input.strip();
        if (!s.startsWith("/")) return Optional.empty();
        // 명령 이름 = 슬래시 직후부터 첫 공백 전까지. 파일 경로(/usr, /tmp …)는 명령이 아니다.
        String body = s.substring(1);
        int sp = body.indexOf(' ');
        String name = sp < 0 ? body : body.substring(0, sp);
        if (!name.matches("[a-zA-Z0-9:_-]+")) return Optional.empty();   // /var/log 등 제외
        String args = sp < 0 ? "" : body.substring(sp + 1).strip();
        return Optional.of(new Parsed(name, args));
    }
}
