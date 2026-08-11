package agent.permission;

import agent.permission.PermissionRule.Behavior;
import agent.tool.PermissionResult;
import agent.tool.Tool;
import agent.tool.ToolContext;

import java.util.Optional;

/**
 * 결정 순서가 곧 보안인 규칙 기반 게이트.
 * deny → ask규칙 → 도구검사 → bypass → allow규칙 → 기본값 순으로 판정한다.
 */
public final class RuleBasedGate implements PermissionGate {

    private final PermissionContext perms;
    private final PermissionPrompt prompt;

    public RuleBasedGate(PermissionContext perms, PermissionPrompt prompt) {
        this.perms = perms; this.prompt = prompt;
    }

    @Override
    public <I> PermissionResult decide(Tool<I, ?> tool, I input, ToolContext ctx) {
        String name = tool.name();
        Optional<String> subject = tool.permissionSubject(input, ctx);

        // ① deny 규칙 → 즉시 거부
        if (anyMatch(Behavior.DENY, name, subject))
            return PermissionResult.deny("권한 규칙(deny)에 의해 거부됨: " + name);

        // ② ask 규칙 → 물어봄 (bypass보다 우선)
        boolean askByRule = anyMatch(Behavior.ASK, name, subject);

        // ③ 도구 자체 안전 검사(위험하면 deny/ask)
        PermissionResult own = tool.checkPermissions(input, ctx);
        if (own instanceof PermissionResult.Deny d) return d;
        boolean askByTool = own instanceof PermissionResult.Ask;

        // !askByRule && !askByTool 가드가 곧 "ask·safety가 bypass/allow보다 우선" 불변식이다.
        // ④ bypass 모드 (①②③를 못 넘는다)
        if (!askByRule && !askByTool && perms.mode() == PermissionMode.BYPASS)
            return PermissionResult.allow();

        // ⑤ allow 규칙
        if (!askByRule && !askByTool && anyMatch(Behavior.ALLOW, name, subject))
            return PermissionResult.allow();

        // ⑤' acceptEdits 모드: 편집 도구 자동 허용
        if (!askByRule && !askByTool && perms.mode() == PermissionMode.ACCEPT_EDITS
                && isEditTool(name))
            return PermissionResult.allow();

        // ⑥ 기본값: 읽기전용이면 통과, 아니면 ask
        boolean mustAsk = askByRule || askByTool || !tool.isReadOnly(input);
        if (!mustAsk) return PermissionResult.allow();

        // ── ask 해소: 사용자에게 물어본다 ──
        return resolveAsk(tool, subject);
    }

    private PermissionResult resolveAsk(Tool<?, ?> tool, Optional<String> subject) {
        switch (prompt.ask(tool.name(), subject)) {
            case ALLOW_ONCE -> { return PermissionResult.allow(); }
            case ALLOW_ALWAYS -> {
                // 규칙으로 저장 → 다음부터 안 물음
                perms.addRule(new PermissionRule(Behavior.ALLOW, tool.name(), subject));
                return PermissionResult.allow();
            }
            default -> { return PermissionResult.deny("사용자가 거부함: " + tool.name()); }
        }
    }

    private boolean anyMatch(Behavior b, String name, Optional<String> subject) {
        return perms.rulesOf(b).stream().anyMatch(r -> r.matches(name, subject));
    }
    private static boolean isEditTool(String name) {
        return name.equals("Write") || name.equals("Edit");
    }
}
