package agent.exec;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class CancellationTokenTest {

    @Test
    void parent_cancel_propagates_to_child_with_reason() {
        var parent = CancellationToken.root();
        var child = parent.child();

        assertThat(child.isCancelled()).isFalse();
        parent.cancel("interrupt");

        assertThat(child.isCancelled()).isTrue();
        assertThat(child.reason()).isEqualTo("interrupt");
        assertThat(child.isInterrupt()).isTrue();
    }

    @Test
    void onCancel_runs_cleanup() {
        var token = CancellationToken.root();
        var cleaned = new AtomicBoolean(false);
        token.onCancel(() -> cleaned.set(true));   // 예: 프로세스 kill

        token.cancel("abort");
        assertThat(cleaned).isTrue();
    }

    @Test
    void cancel_is_idempotent() {
        var token = CancellationToken.root();
        token.cancel("first");
        token.cancel("second");
        assertThat(token.reason()).isEqualTo("first");   // 첫 이유 유지
    }

    @Test
    void child_cancel_does_not_touch_parent_or_siblings() {
        var parent = CancellationToken.root();
        var a = parent.child();
        var b = parent.child();

        a.cancel("local");
        assertThat(a.isCancelled()).isTrue();
        assertThat(b.isCancelled()).isFalse();      // 형제는 멀쩡
        assertThat(parent.isCancelled()).isFalse(); // 부모도 멀쩡
    }

    @Test
    void onCancel_after_already_cancelled_runs_immediately() {
        var token = CancellationToken.root();
        token.cancel("abort");
        var ran = new AtomicBoolean(false);
        token.onCancel(() -> ran.set(true));        // 이미 취소됨 → 즉시 실행
        assertThat(ran).isTrue();
    }
}
