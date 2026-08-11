package agent.subagent;

import agent.message.ContentBlock;
import agent.tool.ToolContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolTest {

    @Test
    void delegates_to_subagent_and_returns_summary() {
        // 가짜 러너: 서브에이전트가 했다고 치고 요약만 돌려줌
        SubagentRunner fakeRunner = (def, prompt, cancel) ->
                "[" + def.type() + "] '" + prompt + "' 완료: 파일 3개 발견";

        var tool = new AgentTool(fakeRunner, Map.of("general", AgentDefinition.GENERAL));
        var input = new AgentTool.Input("디렉터리 조사", "src/ 구조를 조사해줘", Optional.empty());

        var result = tool.call(input, ToolContext.of(Path.of(".")));
        assertThat(result.data()).contains("파일 3개 발견");
        assertThat(result.data()).contains("[general]");   // 기본 종류로 위임됨

        var block = tool.mapResult(result.data(), "tu");
        assertThat(((ContentBlock.TextBlock) block.content().get(0)).text()).contains("완료");
        assertThat(block.isError()).isFalse();
    }

    @Test
    void unknown_subagent_type_falls_back_to_general() {
        SubagentRunner fakeRunner = (def, prompt, cancel) -> def.type();

        var tool = new AgentTool(fakeRunner, Map.of("general", AgentDefinition.GENERAL));
        var input = new AgentTool.Input("조사", "조사해줘", Optional.of("does-not-exist"));

        var result = tool.call(input, ToolContext.of(Path.of(".")));
        assertThat(result.data()).isEqualTo("general");   // 모르는 종류 → GENERAL로 폴백
    }

    @Test
    void input_schema_marks_optional_subagent_type_as_not_required() {
        var tool = new AgentTool((def, prompt, cancel) -> "", Map.of());
        var schema = tool.inputSchema();

        // description/prompt는 필수, subagentType(Optional)은 required에서 빠진다.
        List<String> required = new ArrayList<>();
        schema.get("required").forEach(n -> required.add(n.asText()));
        assertThat(required).contains("description", "prompt");
        assertThat(required).doesNotContain("subagentType");
        // strict: 모르는 필드 거부
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
    }
}
