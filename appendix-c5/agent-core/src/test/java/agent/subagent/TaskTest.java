package agent.subagent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskTest {

    @Test
    void pending_and_running_are_not_terminal() {
        var s = new Task.State("a1", Task.Status.PENDING, "조사", null);
        assertThat(s.isTerminal()).isFalse();
        assertThat(s.withStatus(Task.Status.RUNNING).isTerminal()).isFalse();
    }

    @Test
    void with_result_transitions_to_completed_and_terminal() {
        var running = new Task.State("a1", Task.Status.RUNNING, "조사", null);
        var done = running.withResult("파일 3개 발견");

        assertThat(done.status()).isEqualTo(Task.Status.COMPLETED);
        assertThat(done.result()).isEqualTo("파일 3개 발견");
        assertThat(done.isTerminal()).isTrue();
        // 원본은 불변 — 그대로 RUNNING
        assertThat(running.status()).isEqualTo(Task.Status.RUNNING);
    }

    @Test
    void failed_and_killed_are_terminal() {
        var base = new Task.State("a1", Task.Status.RUNNING, "조사", null);
        assertThat(base.withStatus(Task.Status.FAILED).isTerminal()).isTrue();
        assertThat(base.withStatus(Task.Status.KILLED).isTerminal()).isTrue();
    }
}
