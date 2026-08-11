package agent.subagent;

import agent.tool.ToolContext;
import agent.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStopToolTest {

    @Test
    void stops_a_running_task(@TempDir Path dir) throws Exception {
        var tasks = new BackgroundTasks(dir);
        CountDownLatch started = new CountDownLatch(1);
        String id = tasks.launch("무한 작업", (cancel, out) -> {
            started.countDown();
            while (!cancel.isCancelled()) Thread.sleep(5);
            return "끝";
        });
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        var tool = new TaskStopTool(tasks);
        ToolResult<String> r = tool.call(new TaskStopTool.Input(id), ToolContext.of(dir));

        assertThat(r.data()).contains("중단했습니다").contains(id);
        assertThat(tasks.state(id).orElseThrow().status()).isEqualTo(Task.Status.KILLED);
        assertThat(tool.mapResult(r.data(), "tu").isError()).isFalse();
    }

    @Test
    void stopping_unknown_task_is_a_noop_message(@TempDir Path dir) {
        var tool = new TaskStopTool(new BackgroundTasks(dir));
        ToolResult<String> r = tool.call(new TaskStopTool.Input("nope"), ToolContext.of(dir));
        assertThat(r.data()).contains("이미 끝났거나 없는 태스크입니다");
    }
}
