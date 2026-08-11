package agent.permission;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 현재 모드 + 규칙 묶음. 항상 허용 시 규칙이 추가된다.
 * onChange는 변경 시 호출되는 콜백 — 16장에선 세션 한정(인메모리)이고,
 * 디스크 영속(settings.json)은 이 책에서는 배선하지 않는다 — 부록 D.1.1 참고.
 */
public final class PermissionContext {
    private PermissionMode mode;
    // 병렬 배치 중 resolveAsk의 addRule과 다른 가상 스레드의 규칙 순회가 겹칠 수 있다 → CopyOnWriteArrayList
    private final List<PermissionRule> rules = new CopyOnWriteArrayList<>();
    private final Runnable onChange;     // 변경 시 settings 저장 콜백(이 책에서는 미배선 — 부록 D.1.1)

    public PermissionContext(PermissionMode mode, List<PermissionRule> initial, Runnable onChange) {
        this.mode = mode; this.rules.addAll(initial); this.onChange = onChange;
    }

    public PermissionMode mode() { return mode; }
    public void setMode(PermissionMode m) { this.mode = m; }

    public List<PermissionRule> rulesOf(PermissionRule.Behavior b) {
        return rules.stream().filter(r -> r.behavior() == b).toList();
    }

    public void addRule(PermissionRule rule) { rules.add(rule); onChange.run(); }
}
