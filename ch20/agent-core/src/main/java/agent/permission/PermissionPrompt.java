package agent.permission;

/** ask로 정해지면 사용자에게 묻는다. 묻는 방법(JLine)은 CLI가, 코어는 인터페이스만 안다(3장 원칙). */
public interface PermissionPrompt {
    enum Answer { ALLOW_ONCE, ALLOW_ALWAYS, DENY }

    /** 사용자에게 도구 실행 여부를 묻는다. 비대화형이면 DENY를 반환하는 구현을 쓴다. */
    Answer ask(String toolName, java.util.Optional<String> subject);

    /** 비대화형(헤드리스): 묻지 않고 거부. */
    static PermissionPrompt denying() { return (t, s) -> Answer.DENY; }
}
