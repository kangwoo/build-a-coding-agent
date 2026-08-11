package agent.subagent;

import agent.message.ContentBlock.ToolResultBlock;
import agent.tool.*;
import agent.tool.schema.Desc;

import java.util.Optional;

/**
 * 백그라운드 태스크의 새 출력을 오프셋부터 증분으로 가져오는 도구(부록 C.5).
 * 모델은 반환된 "다음 오프셋"을 다음 호출에 넘겨 이어 읽는다.
 */
public final class TaskOutputTool implements Tool<TaskOutputTool.Input, String> {

    private final BackgroundTasks tasks;

    public TaskOutputTool(BackgroundTasks tasks) { this.tasks = tasks; }

    public record Input(
            @Desc("출력을 가져올 백그라운드 태스크 id") String taskId,
            @Desc("이전 호출에서 받은 다음 오프셋(처음엔 생략 또는 0)") Optional<Long> offset) {}

    @Override public String name() { return "TaskOutput"; }
    @Override public String description() {
        return "백그라운드 태스크의 새 출력을 오프셋부터 증분으로 가져온다. "
             + "반환된 '다음 오프셋'을 다음 호출에 넘겨 이어 읽는다.";
    }
    @Override public Class<Input> inputType() { return Input.class; }

    @Override public boolean isReadOnly(Input in) { return true; }        // 파일을 읽기만 한다
    @Override public boolean isConcurrencySafe(Input in) { return true; }  // 동시 실행 안전

    @Override
    public ToolResult<String> call(Input in, ToolContext ctx) {
        if (tasks.state(in.taskId()).isEmpty()) {
            return ToolResult.of("그런 태스크가 없습니다: " + in.taskId());
        }
        BackgroundTasks.Poll p = tasks.output(in.taskId(), in.offset().orElse(0L));
        String head = "상태: " + p.status() + " | 다음 오프셋: " + p.nextOffset();
        String body = p.text().isEmpty() ? "(새 출력 없음)" : p.text();
        return ToolResult.of(head + "\n" + body);
    }

    @Override
    public ToolResultBlock mapResult(String output, String toolUseId) {
        return ToolResultBlock.ok(toolUseId, output);
    }
}
