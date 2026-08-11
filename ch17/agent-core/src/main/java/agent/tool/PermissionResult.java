package agent.tool;

/** 권한 결정. 16장에서 규칙·모드·대화형 프롬프트로 확장된다. */
public sealed interface PermissionResult
        permits PermissionResult.Allow, PermissionResult.Ask, PermissionResult.Deny {

    static PermissionResult allow() { return Allow.INSTANCE; }
    static PermissionResult ask(String message) { return new Ask(message); }
    static PermissionResult deny(String message) { return new Deny(message); }

    record Allow() implements PermissionResult { static final Allow INSTANCE = new Allow(); }
    record Ask(String message) implements PermissionResult {}
    record Deny(String message) implements PermissionResult {}
}
