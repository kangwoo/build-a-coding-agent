package agent.subagent;

import agent.exec.CancellationToken;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 백그라운드 태스크 저장소. 23장의 비동기 골격을 완성한 형태다(부록 C.5).
 *
 * 세 가지가 핵심이다.
 *  ① 진행 출력을 메모리가 아니라 디스크 파일에 append하고, 오프셋으로 증분 읽는다 — 큰 출력에도 안전하다.
 *  ② 각 태스크는 부모와 *분리된* 취소 토큰을 가진다 — 메인을 Ctrl+C로 끊어도 살아남고, 명시적 stop으로만 죽는다.
 *  ③ 완료 상태 전이를 정리(워크트리 삭제 등)보다 *먼저* 한다 — 결과를 기다리는 쪽이 즉시 깨어난다.
 *
 * 여러 가상 스레드가 동시에 launch/output/stop을 호출해도 안전하다.
 */
public final class BackgroundTasks {

    /** 백그라운드에서 돌릴 작업. out으로 진행 출력을 흘리고 최종 요약을 반환한다. cancel이 끊기면 협조적으로 멈춘다. */
    @FunctionalInterface
    public interface Work {
        String run(CancellationToken cancel, OutputSink out) throws Exception;
    }

    /** 진행 출력을 디스크로 흘려보내는 싱크. */
    @FunctionalInterface
    public interface OutputSink {
        void append(String chunk);
    }

    /** output() 한 번의 결과: offset부터 새로 읽은 텍스트 + 다음에 넘길 오프셋 + 현재 상태. */
    public record Poll(String text, long nextOffset, Task.Status status) {
        public boolean done() {
            return status == Task.Status.COMPLETED
                || status == Task.Status.FAILED
                || status == Task.Status.KILLED;
        }
    }

    /** 태스크 하나의 가변 상태를 묶는다. state는 종료 전이 시점에만 바뀐다. */
    private static final class Handle {
        volatile Task.State state;
        final CancellationToken cancel;
        final Path outFile;
        Handle(Task.State state, CancellationToken cancel, Path outFile) {
            this.state = state; this.cancel = cancel; this.outFile = outFile;
        }
    }

    private final Path baseDir;
    private final Map<String, Handle> handles = new ConcurrentHashMap<>();
    private final Queue<String> notifications = new ConcurrentLinkedQueue<>();
    private int seq = 0;

    public BackgroundTasks(Path baseDir) {
        this.baseDir = baseDir;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 작업을 백그라운드 가상 스레드에 띄우고 즉시 태스크 id를 돌려준다(블로킹하지 않는다). */
    public synchronized String launch(String description, Work work) {
        String id = "a" + (++seq);                       // 데모용 단조 증가 id(실제로는 UUID를 쓴다)
        Path outFile = baseDir.resolve(id + ".out");
        try {
            // 출력 파일을 비워 둔다(이전 실행 잔재 제거).
            Files.writeString(outFile, "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        CancellationToken cancel = CancellationToken.root();   // 부모와 분리 — 메인 취소가 전파되지 않는다
        Handle h = new Handle(
                new Task.State(id, Task.Status.RUNNING, description, null), cancel, outFile);
        handles.put(id, h);

        OutputSink sink = chunk -> append(outFile, chunk);
        Thread.ofVirtual().name("bgtask-" + id).start(() -> runTask(h, work, sink));
        return id;
    }

    private void runTask(Handle h, Work work, OutputSink sink) {
        try {
            String result = work.run(h.cancel, sink);
            finishIfRunning(h, h.state.withResult(result));        // 정상 완료 → COMPLETED
        } catch (Exception e) {
            if (h.cancel.isCancelled()) {
                finishIfRunning(h, h.state.withStatus(Task.Status.KILLED));   // 취소로 인한 종료
            } else {
                append(h.outFile, "\n[오류] " + e);
                finishIfRunning(h, h.state.withStatus(Task.Status.FAILED));   // 진짜 실패
            }
        }
    }

    /**
     * 종료 상태로 전이하고 알림을 남긴다. 이미 종료됐으면 아무 것도 하지 않는다(중복 알림 방지).
     * 핵심: 상태 전이와 알림을 *먼저* 하고, 외부 정리는 이 다음에 둔다 — 기다리는 쪽이 즉시 깨어나도록.
     */
    private void finishIfRunning(Handle h, Task.State terminal) {
        synchronized (h) {
            if (h.state.isTerminal()) return;
            h.state = terminal;
        }
        notifications.add("<task-notification>" + terminal.id() + " "
                + terminal.status() + "</task-notification>");
    }

    /** offset부터 EOF까지 새로 쌓인 출력을 읽어 돌려준다. 파일 바이트 오프셋으로 증분 읽는다. */
    public Poll output(String id, long offset) {
        Handle h = handles.get(id);
        if (h == null) throw new NoSuchElementException("그런 태스크가 없다: " + id);
        try {
            long size = Files.size(h.outFile);
            if (offset >= size) return new Poll("", size, h.state.status());   // 새 출력 없음
            try (SeekableByteChannel ch = Files.newByteChannel(h.outFile, StandardOpenOption.READ)) {
                ch.position(offset);
                ByteBuffer buf = ByteBuffer.allocate((int) (size - offset));
                while (buf.hasRemaining() && ch.read(buf) > 0) { /* size까지 채운다 */ }
                // append는 항상 문자열 전체를 쓰므로 [offset, size) 경계는 UTF-8 문자 경계와 어긋나지 않는다.
                String text = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
                return new Poll(text, offset + buf.position(), h.state.status());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 실행 중인 태스크를 강제 종료한다. 취소 토큰을 끊어 작업이 협조적으로 멈추게 하고, KILLED로 전이한다. */
    public boolean stop(String id) {
        Handle h = handles.get(id);
        if (h == null || h.state.isTerminal()) return false;
        h.cancel.cancel("kill");                          // 작업 쪽 onCancel(프로세스 kill 등)을 실행시킨다
        finishIfRunning(h, h.state.withStatus(Task.Status.KILLED));   // 작업이 협조하지 않아도 상태는 즉시 확정
        return true;
    }

    public Optional<Task.State> state(String id) {
        Handle h = handles.get(id);
        return h == null ? Optional.empty() : Optional.of(h.state);
    }

    /** 쌓인 완료 알림을 모두 꺼내 비운다(REPL이 매 턴 폴링해 사용자에게 보여 준다). */
    public List<String> drainNotifications() {
        List<String> out = new ArrayList<>();
        for (String n; (n = notifications.poll()) != null; ) out.add(n);
        return out;
    }

    private void append(Path file, String chunk) {
        try {
            Files.writeString(file, chunk, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
