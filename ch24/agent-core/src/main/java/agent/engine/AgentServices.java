package agent.engine;

import agent.context.compact.ContextManager;
import agent.cost.CostTracker;
import agent.hook.HookRunner;
import agent.permission.PermissionGate;
import agent.session.TranscriptStore;

/**
 * AgentEngine의 교차 관심사 의존성 묶음. 12장에서 비워 둔 이음매에 필드를 더해 간다
 * (16장 게이트, 17장 훅, 19장 압축·비용, 20장 영속성). 기존 필드는 재배치하지 않고 뒤에 더한다.
 * contextManager·costTracker·transcript는 선택적(null이면 해당 단계를 건너뛴다).
 */
public record AgentServices(
        PermissionGate gate,
        HookRunner hooks,
        ContextManager contextManager,    // null이면 압축 안 함
        CostTracker costTracker,          // null이면 비용 추적 안 함
        TranscriptStore transcript        // null이면 기록 안 함(20장)
) {
    /** 테스트·서브에이전트용 기본값(권한 allow-all, 훅 off, 압축·비용·영속성 off). */
    public static AgentServices defaults() {
        return new AgentServices(PermissionGate.allowAll(), HookRunner.none(), null, null, null);
    }
}
