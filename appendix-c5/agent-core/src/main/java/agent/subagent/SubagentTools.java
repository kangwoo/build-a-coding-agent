package agent.subagent;

import agent.tool.Tool;
import agent.tool.ToolRegistry;
import agent.tool.builtin.BuiltinTools;

/** 서브에이전트의 도구 풀을 만드는 작은 팩토리. */
public final class SubagentTools {
    private SubagentTools() {}

    /**
     * {@link AgentDefinition#allowedTools()}에 들어 있는 도구만 골라 새 레지스트리를 만든다.
     * {@code Agent}(=AgentTool)는 무조건 제외한다 — 서브가 또 서브를 띄우는 무한 재귀를 막기 위해서다.
     * allowedTools에 들어 있지만 이 스냅샷에 없는 이름(예: 15장 전의 "Bash")은 그냥 무시된다.
     */
    public static ToolRegistry poolFor(AgentDefinition def) {
        ToolRegistry pool = new ToolRegistry();
        for (Tool<?, ?> t : BuiltinTools.registry().all()) {
            if (t.name().equals("Agent")) continue;                 // 재귀 방지: AgentTool은 절대 넣지 않음
            if (def.allowedTools().contains(t.name())) pool.register(t);
        }
        return pool;
    }
}
