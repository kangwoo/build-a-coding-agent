package agent.permission;

/**
 * 권한 모드. dontAsk(ask→deny)·auto(분류기) 같은 다른 모드도 있으나
 * 이 책은 핵심 4개로 단순화한다.
 */
public enum PermissionMode {
    DEFAULT, ACCEPT_EDITS, BYPASS,
    PLAN   // 실전 에이전트의 계획 모드 자리 — 이 책에서는 선언만 하고 쓰지 않는다
}
