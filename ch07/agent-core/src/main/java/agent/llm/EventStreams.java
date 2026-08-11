// agent-core/src/main/java/agent/llm/EventStreams.java
package agent.llm;

import java.time.Duration;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

public final class EventStreams {
    private EventStreams() {}

    /** sink로 이벤트를 emit하는 본문을 가상 스레드에서 돌리고, 그 결과를 순회 가능하게 한다. */
    public static <T> EventStream<T> fromProducer(StreamBody<T> body) {
        return new QueueEventStream<>(body);
    }

    /** 생산자 본문. 체크 예외를 던질 수 있다(HTTP/IO). */
    public interface StreamBody<T> { void produce(Sink<T> sink) throws Exception; }

    public interface Sink<T> { void emit(T item); }

    private static final class QueueEventStream<T> implements EventStream<T> {
        private static final System.Logger LOG = System.getLogger(EventStreams.class.getName());
        private static final Object END = new Object();
        private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final Thread producer;

        QueueEventStream(StreamBody<T> body) {
            this.producer = Thread.ofVirtual().name("llm-stream").start(() -> {
                try {
                    body.produce(item -> queue.add(item));
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    queue.add(END);   // 끝(또는 오류) 표시
                }
            });
        }

        /** 주의: one-shot이다 — 큐를 소비하므로 두 번째 iterator()는 처음이 아니라 남은 이벤트부터 본다. */
        @Override public Iterator<T> iterator() {
            return new Iterator<>() {
                private Object peeked;
                private boolean done;

                @Override public boolean hasNext() {
                    if (done) return false;
                    if (peeked == null) {
                        try { peeked = queue.take(); }      // 블로킹 대기
                        catch (InterruptedException e) {
                            // 인터럽트는 '정상 종료'가 아니다 — 잘린 스트림이 완결 메시지처럼 보이면 안 된다.
                            Thread.currentThread().interrupt();
                            throw new StreamCancelledException("스트림 소비가 인터럽트됨");
                        }
                    }
                    if (peeked == END) {
                        done = true;
                        Throwable t = error.get();
                        if (t != null) throw new RuntimeException("스트림 실패", t);
                        return false;
                    }
                    return true;
                }

                @Override @SuppressWarnings("unchecked")
                public T next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    T item = (T) peeked; peeked = null; return item;
                }
            };
        }

        @Override public void close() {
            producer.interrupt();                            // 블로킹 IO·take를 깨운다
            boolean interrupted = Thread.interrupted();      // 플래그를 걷어둔다 — 세워둔 채면 join이 기다리지 않고 튕긴다
            try {
                // 생산자 종료까지 대기 — 이전 턴이 다음 턴과 겹치지 않게(상한 5초)
                if (!producer.join(Duration.ofSeconds(5))) {
                    // false = 상한 초과. 침묵하면 좀비 생산자가 정상 종료로 위장된다 —
                    // 코어는 화면을 모르므로 JDK 플랫폼 로거로만 알린다(기본 stderr, 임베더가 라우팅 가능).
                    LOG.log(System.Logger.Level.WARNING,
                            "생산자가 5초 안에 종료되지 않음 — 다음 턴과 겹칠 수 있음");
                }
            } catch (InterruptedException e) { interrupted = true; }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }
}
