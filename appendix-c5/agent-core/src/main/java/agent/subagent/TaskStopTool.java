package agent.subagent;

import agent.message.ContentBlock.ToolResultBlock;
import agent.tool.*;
import agent.tool.schema.Desc;

/** 실행 중인 백그라운드 태스크를 강제 종료하는 도구(부록 C.5). 분리된 취소 토큰을 끊어 KILLED로 전이한다. */
public final class TaskStopTool implements Tool<TaskStopTool.Input, String> {

    private final BackgroundTasks tasks;

    public TaskStopTool(BackgroundTasks tasks) { this.tasks = tasks; }

    public record Input(@Desc("중단할 백그라운드 태스크 id") String taskId) {}

    @Override public String name() { return "TaskStop"; }
    @Override public String description() {
        return "실행 중인 백그라운드 태스크를 강제 종료한다(KILLED). 이미 끝난 태스크에는 효과가 없다.";
    }
    @Override public Class<Input> inputType() { return Input.class; }

    @Override public boolean isReadOnly(Input in) { return false; }        // 상태를 바꾼다
    @Override public boolean isConcurrencySafe(Input in) { return false; }  // 종료는 직렬로

    @Override
    public ToolResult<String> call(Input in, ToolContext ctx) {
        boolean stopped = tasks.stop(in.taskId());
        return ToolResult.of(stopped
                ? "중단했습니다: " + in.taskId()
                : "이미 끝났거나 없는 태스크입니다: " + in.taskId());
    }

    @Override
    public ToolResultBlock mapResult(String output, String toolUseId) {
        return ToolResultBlock.ok(toolUseId, output);
    }
}
