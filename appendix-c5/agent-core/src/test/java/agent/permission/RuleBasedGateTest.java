package agent.permission;

import agent.message.ContentBlock.ToolResultBlock;   // ToolResultBlock은 agent.message에 있다
import agent.permission.PermissionRule.Behavior;
import agent.tool.*;
import agent.tool.builtin.EchoTool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedGateTest {

    /** 더미 도구용 빈 입력 — raw 타입 대신 record 하나면 캐스트 없이 Tool<I, O>가 성립한다. */
    record NoInput() {}

    /** 위험(읽기전용 아님) 더미 도구. */
    static final class Dangerous implements Tool<NoInput, String> {
        public String name() { return "Danger"; }
        public String description() { return "위험"; }
        public Class<NoInput> inputType() { return NoInput.class; }
        public ToolResult<String> call(NoInput in, ToolContext ctx) { return ToolResult.of("did it"); }
        public ToolResultBlock mapResult(String o, String id) { return ToolResultBlock.ok(id, o); }
        // isReadOnly 기본 false → 기본적으로 ask
    }

    private PermissionContext ctx(PermissionMode mode, List<PermissionRule> rules) {
        return new PermissionContext(mode, rules, () -> {});
    }
    private final ToolContext tctx = ToolContext.of(Path.of("."));

    @Test
    void deny_rule_beats_bypass_mode() {
        var perms = ctx(PermissionMode.BYPASS,
                List.of(new PermissionRule(Behavior.DENY, "Danger", java.util.Optional.empty())));
        var gate = new RuleBasedGate(perms, PermissionPrompt.denying());

        // bypass라도 deny 규칙이 이긴다(순서가 보안)
        assertThat(gate.decide(new Dangerous(), new NoInput(), tctx))
                .isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    void readonly_tool_allowed_by_default() {
        var perms = ctx(PermissionMode.DEFAULT, List.of());
        var gate = new RuleBasedGate(perms, PermissionPrompt.denying());
        // Echo는 isReadOnly=true → 묻지 않고 allow
        assertThat(gate.decide(new EchoTool(), new EchoTool.Input("hi"), tctx))
                .isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    void dangerous_tool_asks_and_always_allow_persists_rule() {
        var perms = ctx(PermissionMode.DEFAULT, new ArrayList<>());
        var gate = new RuleBasedGate(perms, (t, s) -> PermissionPrompt.Answer.ALLOW_ALWAYS);

        assertThat(gate.decide(new Dangerous(), new NoInput(), tctx))
                .isInstanceOf(PermissionResult.Allow.class);
        // '항상 허용' → 규칙으로 저장됨
        assertThat(perms.rulesOf(Behavior.ALLOW)).anyMatch(r -> r.toolName().equals("Danger"));
    }

    @Test
    void bypass_mode_allows_dangerous_without_rule() {
        var perms = ctx(PermissionMode.BYPASS, List.of());
        var gate = new RuleBasedGate(perms, PermissionPrompt.denying());
        // bypass면 deny/ask 규칙·도구ask가 없을 때 위험 도구도 통과
        assertThat(gate.decide(new Dangerous(), new NoInput(), tctx))
                .isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    void ask_rule_beats_bypass_and_prompts() {
        // ask 규칙은 bypass보다 우선 → 프롬프트가 떠야 한다(여기선 denying이라 거부)
        var perms = ctx(PermissionMode.BYPASS,
                List.of(new PermissionRule(Behavior.ASK, "Danger", java.util.Optional.empty())));
        var gate = new RuleBasedGate(perms, PermissionPrompt.denying());
        assertThat(gate.decide(new Dangerous(), new NoInput(), tctx))
                .isInstanceOf(PermissionResult.Deny.class);   // 물었고, 사용자가 거부
    }

    @Test
    void allow_rule_with_content_prefix_matches_bash_command() {
        var perms = ctx(PermissionMode.DEFAULT,
                List.of(PermissionRule.parse(Behavior.ALLOW, "Bash(npm install)")));
        var gate = new RuleBasedGate(perms, PermissionPrompt.denying());
        var bash = new agent.tool.builtin.BashTool();
        var input = new agent.tool.builtin.BashTool.Input(
                "npm install lodash", java.util.Optional.empty(), java.util.Optional.empty());
        // "npm install"로 시작하는 명령은 allow 규칙에 매치 → 통과
        assertThat(gate.decide(bash, input, tctx))
                .isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    void deny_rule_beats_allow_rule() {
        // 같은 도구에 allow·deny가 동시에 걸리면 deny가 이긴다 — ①이 ⑤보다 먼저(순서가 보안)
        var perms = ctx(PermissionMode.DEFAULT, List.of(
                new PermissionRule(Behavior.ALLOW, "Danger", java.util.Optional.empty()),
                new PermissionRule(Behavior.DENY, "Danger", java.util.Optional.empty())));
        var gate = new RuleBasedGate(perms, PermissionPrompt.denying());
        assertThat(gate.decide(new Dangerous(), new NoInput(), tctx))
                .isInstanceOf(PermissionResult.Deny.class);
    }

    /** 이름만 Edit인 위험(읽기전용 아님) 더미 — acceptEdits(⑤')가 이름으로 판별하는지 확인용. */
    static final class FakeEdit implements Tool<NoInput, String> {
        public String name() { return "Edit"; }
        public String description() { return "편집"; }
        public Class<NoInput> inputType() { return NoInput.class; }
        public ToolResult<String> call(NoInput in, ToolContext ctx) { return ToolResult.of("edited"); }
        public ToolResultBlock mapResult(String o, String id) { return ToolResultBlock.ok(id, o); }
    }

    @Test
    void accept_edits_mode_auto_allows_edit_tools_only() {
        var perms = ctx(PermissionMode.ACCEPT_EDITS, List.of());
        var gate = new RuleBasedGate(perms, PermissionPrompt.denying());

        // 편집 도구(Write·Edit)만 자동 허용(⑤')
        assertThat(gate.decide(new FakeEdit(), new NoInput(), tctx))
                .isInstanceOf(PermissionResult.Allow.class);
        // 비편집 위험 도구는 여전히 ask → 비대화형(denying)이라 거부
        assertThat(gate.decide(new Dangerous(), new NoInput(), tctx))
                .isInstanceOf(PermissionResult.Deny.class);
    }
}
