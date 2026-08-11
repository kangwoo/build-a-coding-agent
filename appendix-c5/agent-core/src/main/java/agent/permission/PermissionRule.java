package agent.permission;

import java.util.Optional;

/** "Bash(npm install)" / "Write" 형태의 권한 규칙. content는 도구마다 의미가 다르다. */
public record PermissionRule(Behavior behavior, String toolName, Optional<String> content) {

    public enum Behavior { ALLOW, DENY, ASK }

    /** "Bash(npm install)" → 규칙으로 파싱. */
    public static PermissionRule parse(Behavior behavior, String spec) {
        int open = spec.indexOf('(');
        if (open < 0) return new PermissionRule(behavior, spec, Optional.empty());
        int close = spec.lastIndexOf(')');
        // 짝이 안 맞으면 조용히 넘기지 않는다 — deny 규칙이 소리 없이 사라지면 보안 구멍이다
        if (close < open)
            throw new IllegalArgumentException("권한 규칙 형식 오류(닫는 괄호 없음): " + spec);
        String tool = spec.substring(0, open);
        String content = spec.substring(open + 1, close);
        return new PermissionRule(behavior, tool, Optional.of(content));
    }

    /** 이 규칙이 (도구이름, 권한 subject)에 매치되나? */
    public boolean matches(String tool, Optional<String> subject) {
        if (!toolName.equals(tool) && !toolName.equals("*")) return false;
        if (content.isEmpty()) return true;                        // 도구 전체 규칙
        return subject.map(s -> s.startsWith(content.get())).orElse(false);  // 접두사 매치
    }

    public String spec() {
        return content.map(c -> toolName + "(" + c + ")").orElse(toolName);
    }
}
