package agent.subagent;

import agent.tool.Tool;
import agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentToolsTest {

    @Test
    void pool_keeps_only_allowed_tools() {
        ToolRegistry pool = SubagentTools.poolFor(AgentDefinition.GENERAL);   // Read/Glob/Grep
        List<String> names = pool.all().stream().map(Tool::name).toList();

        assertThat(names).contains("Read", "Glob", "Grep");
        // 허용되지 않은 도구(Write/Edit/Bash 등)는 빠진다.
        assertThat(names).doesNotContain("Write", "Edit", "Bash", "WebFetch");
    }

    @Test
    void pool_never_contains_agent_tool_even_if_allowed() {
        // 재귀 방지 불변식: allowedTools에 "Agent"가 명시돼도 풀에는 절대 들어가지 않는다.
        var def = new AgentDefinition("recursive", "sys", Set.of("Read", "Agent"));
        ToolRegistry pool = SubagentTools.poolFor(def);

        assertThat(pool.find("Agent")).isEmpty();
        assertThat(pool.all().stream().map(Tool::name)).contains("Read");
    }

    @Test
    void unknown_tool_names_are_ignored() {
        // 이 스냅샷에 없는 이름은 그냥 무시된다(빈 풀이 되거나 예외를 던지지 않는다).
        var def = new AgentDefinition("x", "sys", Set.of("Read", "DoesNotExist"));
        ToolRegistry pool = SubagentTools.poolFor(def);

        assertThat(pool.all().stream().map(Tool::name)).containsExactly("Read");
    }
}
