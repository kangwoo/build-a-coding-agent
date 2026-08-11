package agent.subagent;

import agent.tool.ToolContext;
import agent.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TaskOutputToolTest {

    private static void awaitTerminal(BackgroundTasks tasks, String id) throws InterruptedException {
        for (int i = 0; i < 200 && !tasks.state(id).orElseThrow().isTerminal(); i++) Thread.sleep(10);
    }

    @Test
    void returns_status_and_advancing_offset(@TempDir Path dir) throws Exception {
        var tasks = new BackgroundTasks(dir);
        String id = tasks.launch("출력 작업", (cancel, out) -> { out.append("hi"); return "끝"; });
        awaitTerminal(tasks, id);

        var tool = new TaskOutputTool(tasks);
        var ctx = ToolContext.of(dir);

        ToolResult<String> first = tool.call(new TaskOutputTool.Input(id, Optional.empty()), ctx);
        assertThat(first.data()).contains("상태: COMPLETED");
        assertThat(first.data()).contains("다음 오프셋: 2");
        assertThat(first.data()).contains("hi");

        // 받은 오프셋으로 다시 폴링하면 새 출력은 없다.
        ToolResult<String> second = tool.call(new TaskOutputTool.Input(id, Optional.of(2L)), ctx);
        assertThat(second.data()).contains("(새 출력 없음)");

        assertThat(tool.mapResult(first.data(), "tu").isError()).isFalse();
    }

    @Test
    void unknown_task_returns_friendly_message(@TempDir Path dir) {
        var tool = new TaskOutputTool(new BackgroundTasks(dir));
        ToolResult<String> r = tool.call(new TaskOutputTool.Input("nope", Optional.empty()), ToolContext.of(dir));
        assertThat(r.data()).contains("그런 태스크가 없습니다");
    }

    @Test
    void schema_marks_offset_optional_but_task_id_required(@TempDir Path dir) {
        var tool = new TaskOutputTool(new BackgroundTasks(dir));
        var schema = tool.inputSchema();

        List<String> required = new ArrayList<>();
        schema.get("required").forEach(n -> required.add(n.asText()));
        assertThat(required).contains("taskId");
        assertThat(required).doesNotContain("offset");
    }
}
