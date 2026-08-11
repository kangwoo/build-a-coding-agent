package agent.engine;

import agent.hook.HookRunner;
import agent.permission.PermissionGate;

/**
 * AgentEngine의 교차 관심사 의존성 묶음. 12장에서 비워 둔 이음매에 필드를 더해 간다
 * (16장 게이트, 17장 훅). 생성자 시그니처는 건드리지 않는다 — defaults()와 REPL 조립부만 손댄다.
 */
public record AgentServices(
        PermissionGate gate,
        HookRunner hooks
        // 19장: ContextManager contextManager, CostTracker costTracker
        // 20장: TranscriptStore transcript
) {
    /** 테스트·서브에이전트용 기본값(권한 allow-all, 훅 off, 압축·영속성 off). */
    public static AgentServices defaults() { return new AgentServices(PermissionGate.allowAll(), HookRunner.none()); }
}
