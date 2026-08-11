package agent.subagent;

import agent.message.ContentBlock.ToolResultBlock;
import agent.tool.*;
import agent.tool.schema.Desc;

import java.util.*;

/** 메인 에이전트가 작업을 위임하는 동기 도구. 서브에이전트의 요약된 결과를 tool_result로 돌려준다. */
public final class AgentTool implements Tool<AgentTool.Input, String> {

    private final SubagentRunner runner;
    private final Map<String, AgentDefinition> definitions;

    public AgentTool(SubagentRunner runner, Map<String, AgentDefinition> definitions) {
        this.runner = runner; this.definitions = definitions;
    }

    public record Input(@Desc("위임할 작업 설명(한 줄)") String description,
                        @Desc("서브에이전트가 수행할 상세 지시") String prompt,
                        @Desc("서브에이전트 종류") Optional<String> subagentType) {}

    @Override public String name() { return "Agent"; }
    @Override public String description() {
        return "독립된 서브에이전트에 작업을 위임하고 요약된 결과를 받는다. "
             + "탐색·조사처럼 컨텍스트를 많이 쓰는 하위 작업에 적합.";
    }
    @Override public Class<Input> inputType() { return Input.class; }
    @Override public boolean isReadOnly(Input in) { return false; }   // 서브가 무엇이든 할 수 있음

    @Override
    public ToolResult<String> call(Input in, ToolContext ctx) {
        AgentDefinition def = definitions.getOrDefault(
                in.subagentType().orElse("general"), AgentDefinition.GENERAL);
        // 동기 실행: 결과가 올 때까지 블로킹. ctx.cancel()로 부모 취소가 전파된다.
        String result = runner.run(def, in.prompt(), ctx.cancel());
        return ToolResult.of(result);
    }

    @Override
    public ToolResultBlock mapResult(String result, String toolUseId) {
        return ToolResultBlock.ok(toolUseId, result);
    }
}
