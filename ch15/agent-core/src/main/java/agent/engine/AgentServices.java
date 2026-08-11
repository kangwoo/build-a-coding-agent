package agent.engine;

/**
 * AgentEngine의 교차 관심사 의존성 묶음. 장이 진행되며 필드가 늘어난다.
 * 지금은 비어 있지만 의도된 이음매(seam)다 — 이후 장에서 필드를 <em>더하기만</em> 하면
 * AgentEngine 생성자 시그니처는 다시 바뀌지 않고, defaults()와 REPL 조립부 두 곳만 손대면 된다.
 */
public record AgentServices(
        // 16장: PermissionGate gate
        // 17장: HookRunner hooks
        // 19장: ContextManager contextManager, CostTracker costTracker
        // 20장: TranscriptStore transcript
) {
    /** 테스트·서브에이전트용 기본값(권한 allow-all, 압축·영속성 off). */
    public static AgentServices defaults() { return new AgentServices(); }
}
