package agent.cli.permission;

import agent.permission.PermissionPrompt;
import org.jline.reader.LineReader;

import java.util.Optional;

/**
 * JLine으로 "[a]llow once / [A]lways / [d]eny"를 읽어 Answer로 돌려준다.
 * 도구 ask는 도구 워커 스레드에서 동기로 일어난다(메인 루프는 이벤트 큐에서 대기 중).
 * 입력 불가(EOF·비대화형)면 안전하게 거부한다(fail-closed).
 */
public final class JLinePermissionPrompt implements PermissionPrompt {
    private final LineReader reader;

    public JLinePermissionPrompt(LineReader reader) { this.reader = reader; }

    @Override
    public Answer ask(String toolName, Optional<String> subject) {
        String what = subject.map(s -> toolName + "(" + s + ")").orElse(toolName);
        String line;
        try {
            line = reader.readLine("⚠ " + what + " 실행을 허용할까요? [a]llow once / [A]lways / [d]eny: ");
        } catch (RuntimeException e) {
            return Answer.DENY;
        }
        if (line == null) return Answer.DENY;
        String t = line.strip();
        if (t.equals("a")) return Answer.ALLOW_ONCE;
        if (t.equals("A")) return Answer.ALLOW_ALWAYS;
        return Answer.DENY;   // 그 외(d 포함)는 거부
    }
}
