package agent.subagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BackgroundTasksTest {

    /** 종료 상태가 될 때까지 짧게 폴링한다(작업은 ms 단위로 끝난다). */
    private static Task.State awaitTerminal(BackgroundTasks tasks, String id) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Task.State s = tasks.state(id).orElseThrow();
            if (s.isTerminal()) return s;
            Thread.sleep(10);
        }
        throw new AssertionError("태스크가 제때 종료되지 않음: " + id);
    }

    @Test
    void completes_and_emits_notification(@TempDir Path dir) throws Exception {
        var tasks = new BackgroundTasks(dir);
        String id = tasks.launch("요약 작업", (cancel, out) -> {
            out.append("작업 시작\n");
            return "파일 3개 발견";
        });

        Task.State done = awaitTerminal(tasks, id);
        assertThat(done.status()).isEqualTo(Task.Status.COMPLETED);
        assertThat(done.result()).isEqualTo("파일 3개 발견");
        assertThat(tasks.drainNotifications())
                .containsExactly("<task-notification>" + id + " COMPLETED</task-notification>");
        // 두 번째 drain은 비어 있다(한 번 꺼내면 사라진다).
        assertThat(tasks.drainNotifications()).isEmpty();
    }

    @Test
    void output_is_incremental_by_byte_offset(@TempDir Path dir) throws Exception {
        var tasks = new BackgroundTasks(dir);
        String id = tasks.launch("출력 작업", (cancel, out) -> {
            out.append("AAAA");
            out.append("BBBB");
            return "끝";
        });
        awaitTerminal(tasks, id);

        BackgroundTasks.Poll first = tasks.output(id, 0);
        assertThat(first.text()).isEqualTo("AAAABBBB");
        assertThat(first.nextOffset()).isEqualTo(8);

        BackgroundTasks.Poll next = tasks.output(id, first.nextOffset());
        assertThat(next.text()).isEmpty();                 // 새 출력 없음
        assertThat(next.nextOffset()).isEqualTo(8);
        assertThat(next.done()).isTrue();
    }

    @Test
    void output_offset_counts_utf8_bytes_not_chars(@TempDir Path dir) throws Exception {
        var tasks = new BackgroundTasks(dir);
        String id = tasks.launch("한글 출력", (cancel, out) -> { out.append("한글"); return "끝"; });
        awaitTerminal(tasks, id);

        BackgroundTasks.Poll p = tasks.output(id, 0);
        assertThat(p.text()).isEqualTo("한글");
        assertThat(p.nextOffset()).isEqualTo(6);           // UTF-8에서 한글 한 자 = 3바이트
    }

    @Test
    void stop_kills_running_task_and_wins_over_late_completion(@TempDir Path dir) throws Exception {
        var tasks = new BackgroundTasks(dir);
        CountDownLatch started = new CountDownLatch(1);
        String id = tasks.launch("무한 작업", (cancel, out) -> {
            started.countDown();
            while (!cancel.isCancelled()) Thread.sleep(5);   // 취소될 때까지 협조적으로 대기
            return "뒤늦은 정상 종료";                          // 이미 KILLED라 이 결과는 무시돼야 한다
        });

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(tasks.state(id).orElseThrow().status()).isEqualTo(Task.Status.RUNNING);

        assertThat(tasks.stop(id)).isTrue();
        assertThat(tasks.state(id).orElseThrow().status()).isEqualTo(Task.Status.KILLED);

        // 작업 스레드가 뒤늦게 끝나도 상태는 KILLED 그대로(중복 알림도 없다).
        Task.State after = awaitTerminal(tasks, id);
        assertThat(after.status()).isEqualTo(Task.Status.KILLED);
        assertThat(tasks.drainNotifications())
                .containsExactly("<task-notification>" + id + " KILLED</task-notification>");
        assertThat(tasks.stop(id)).isFalse();              // 이미 종료된 태스크 stop은 no-op
    }

    @Test
    void failed_work_transitions_to_failed_and_records_error(@TempDir Path dir) throws Exception {
        var tasks = new BackgroundTasks(dir);
        String id = tasks.launch("터지는 작업", (cancel, out) -> {
            out.append("진행 중\n");
            throw new IllegalStateException("펑");
        });

        Task.State done = awaitTerminal(tasks, id);
        assertThat(done.status()).isEqualTo(Task.Status.FAILED);
        assertThat(tasks.output(id, 0).text()).contains("진행 중").contains("[오류]").contains("펑");
    }
}
