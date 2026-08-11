// agent-core/src/test/java/agent/llm/EventStreamsTest.java
package agent.llm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventStreamsTest {

    @Test
    void consumer_interrupt_surfaces_as_cancellation_not_normal_end() {
        var gate = new CountDownLatch(1);   // 열리지 않는 문 — 생산자를 이벤트 하나 뒤에서 멈춰 세운다
        try (EventStream<String> s = EventStreams.fromProducer(sink -> {
            sink.emit("부분");
            gate.await();                   // close()의 인터럽트가 깨울 때까지 END를 내지 않는다
        })) {
            var it = s.iterator();
            assertThat(it.next()).isEqualTo("부분");   // 이미 도착한 이벤트는 정상 소비

            // 소비 스레드 인터럽트 연출: 다음 대기(take)는 정상 종료로 위장되면 안 된다
            Thread.currentThread().interrupt();
            assertThatThrownBy(it::hasNext)
                    .isInstanceOf(StreamCancelledException.class)  // 전용 타입 — IllegalStateException보다 먼저 골라 잡을 수 있다
                    .isInstanceOf(CancellationException.class);    // 취소 계보 유지(표준 취소 catch에도 걸린다)

            // 인터럽트 플래그는 복원돼 있어야 한다(확인하며 걷는다 — 세워 둔 채면 close()의 join이 대기 없이 튕긴다)
            assertThat(Thread.interrupted()).isTrue();
        }
    }
}
