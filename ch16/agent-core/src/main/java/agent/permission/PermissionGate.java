package agent.permission;

import agent.tool.PermissionResult;
import agent.tool.Tool;
import agent.tool.ToolContext;

public interface PermissionGate {
    /** Ask까지 해소한 최종 결정(Allow 또는 Deny). */
    <I> PermissionResult decide(Tool<I, ?> tool, I input, ToolContext ctx);

    /**
     * 규칙·프롬프트 없이 허용(테스트·헤드리스 신뢰 환경용).
     * 단 도구 자체 안전 검사(checkPermissions)의 Deny는 존중한다 — 이 면역(bypass-immune)이
     * 16장 결정 순서의 핵심 불변식이라, allow-all에서도 깨면 안 된다.
     */
    static PermissionGate allowAll() {
        return new PermissionGate() {
            public <I> PermissionResult decide(Tool<I, ?> tool, I input, ToolContext ctx) {
                PermissionResult own = tool.checkPermissions(input, ctx);
                return own instanceof PermissionResult.Deny ? own : PermissionResult.allow();
            }
        };
    }
}
