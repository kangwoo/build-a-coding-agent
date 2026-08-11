package agent.engine;

import agent.permission.PermissionGate;

/**
 * AgentEngine의 교차 관심사 의존성 묶음. 12장에서 비워 둔 이음매에 16장이 첫 필드(게이트)를 더한다.
 * 생성자 시그니처는 건드리지 않는다 — 이후 장(17·19·20)이 필드만 더한다.
 */
public record AgentServices(
        PermissionGate gate
        // 17장: HookRunner hooks
        // 19장: ContextManager contextManager, CostTracker costTracker
        // 20장: TranscriptStore transcript
) {
    /** 테스트·서브에이전트용 기본값(권한 allow-all, 압축·영속성 off). */
    public static AgentServices defaults() { return new AgentServices(PermissionGate.allowAll()); }
}
