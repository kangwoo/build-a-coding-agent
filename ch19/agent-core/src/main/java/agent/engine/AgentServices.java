package agent.engine;

import agent.context.compact.ContextManager;
import agent.cost.CostTracker;
import agent.hook.HookRunner;
import agent.permission.PermissionGate;

/**
 * AgentEngine의 교차 관심사 의존성 묶음. 12장에서 비워 둔 이음매에 필드를 더해 간다
 * (16장 게이트, 17장 훅, 19장 압축·비용). 생성자 시그니처는 건드리지 않는다.
 * contextManager·costTracker는 선택적(null이면 해당 단계를 건너뛴다).
 */
public record AgentServices(
        PermissionGate gate,
        HookRunner hooks,
        ContextManager contextManager,    // null이면 압축 안 함
        CostTracker costTracker           // null이면 비용 추적 안 함
        // 20장: TranscriptStore transcript
) {
    /** 테스트·서브에이전트용 기본값(권한 allow-all, 훅 off, 압축·비용 off). */
    public static AgentServices defaults() {
        return new AgentServices(PermissionGate.allowAll(), HookRunner.none(), null, null);
    }
}
