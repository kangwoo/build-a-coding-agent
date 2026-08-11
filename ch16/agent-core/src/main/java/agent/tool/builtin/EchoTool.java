package agent.tool.builtin;

import agent.message.ContentBlock.ToolResultBlock;
import agent.tool.*;

public final class EchoTool implements Tool<EchoTool.Input, String> {

    public record Input(@agent.tool.schema.Desc("돌려줄 텍스트") String text) {}

    @Override public String name() { return "Echo"; }
    @Override public String description() { return "입력 text를 그대로 돌려준다(테스트용)."; }
    @Override public Class<Input> inputType() { return Input.class; }

    @Override public boolean isReadOnly(Input input) { return true; }       // 읽기 전용임을 명시
    @Override public boolean isConcurrencySafe(Input input) { return true; } // 동시 실행 안전

    @Override public ToolResult<String> call(Input input, ToolContext ctx) {
        return ToolResult.of(input.text());
    }

    @Override public ToolResultBlock mapResult(String output, String toolUseId) {
        return ToolResultBlock.ok(toolUseId, output);
    }
}
