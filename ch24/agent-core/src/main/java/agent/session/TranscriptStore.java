package agent.session;

import agent.message.Json;
import agent.message.Message;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * append-only JSONL 쓰기. 쓰기를 핫 패스에서 떼어내 전용 가상 스레드로 흘려보낸다.
 *
 * <p>스레드 모델: record()는 엔진 스레드에서 호출돼 dedup + parentUuid 연결을 끝내고
 * 직렬화된 줄과 대상 경로를 큐에 넣는다. 가상 스레드는 큐에서 줄을 모아(100ms 디바운스)
 * 줄에 박힌 경로에 덧붙이기만 하므로 가변 체인 상태(lastUuid)가 스레드를 건너지 않는다.
 *
 * <p>세션 정체성(sessionId·경로)은 record() 시점에 한 번만 샘플링한다. 경로를 드레인 시점에
 * 다시 읽으면 그 사이 switchSession()이 끼었을 때 이전 세션 줄이 새 세션 파일로 간다.
 * 세션이 바뀌면 parentUuid 체인도 새로 시작한다.
 */
public final class TranscriptStore implements AutoCloseable {

    /** 큐 항목: record() 시점에 확정한 대상 경로와 직렬화된 줄. */
    private record Line(Path path, String json) {}

    private static final Line POISON = new Line(null, null);
    private static final long FLUSH_INTERVAL_MS = 100;

    private final SessionContext session;
    private final BlockingQueue<Line> queue = new LinkedBlockingQueue<>();
    private final Set<String> recorded = ConcurrentHashMap.newKeySet();
    private final Thread writer;
    private String lastUuid = null;
    private String lastSessionId = null;

    public TranscriptStore(SessionContext session) {
        this.session = session;
        this.writer = Thread.ofVirtual().name("transcript-writer").start(this::drain);
    }

    /** 메시지를 기록(중복 dedup, parentUuid 자동 연결). 엔진 스레드에서 호출. */
    public void record(Message m) {
        if (!recorded.add(m.uuid())) return;                        // 이미 기록됨
        var id = session.identity();                                // 정체성은 여기서 한 번만 샘플링
        if (!id.sessionId().equals(lastSessionId)) {
            lastSessionId = id.sessionId();
            lastUuid = null;                                        // 새 세션 = 새 체인
        }
        var entry = new TranscriptEntry(m, lastUuid, id.sessionId());
        lastUuid = m.uuid();
        queue.add(new Line(session.transcriptPath(id), Json.write(entry)));
    }

    /** 큐를 비우며 100ms 디바운스로 모아 append. POISON을 만나면 잔여를 비우고 종료. */
    private void drain() {
        boolean warned = false;
        try {
            boolean running = true;
            while (running) {
                Line first = queue.take();                          // 첫 줄을 기다린다
                List<Line> batch = new ArrayList<>();
                if (first == POISON) running = false; else batch.add(first);

                Line more;                                          // 디바운스: 그 사이 쌓인 줄을 모은다
                while ((more = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)) != null) {
                    if (more == POISON) { running = false; break; }
                    batch.add(more);
                }
                try {
                    flush(batch);
                } catch (IOException e) {
                    // 여기서 죽으면 이후 record()가 아무도 안 비우는 큐에 쌓여 조용히 유실된다.
                    // 경고만 남기고(도배 방지로 1회) 루프는 계속 돈다.
                    if (!warned) {
                        System.err.println("transcript 기록 실패: " + e);
                        warned = true;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 경로가 같은 연속 구간을 모아 append. 배치 하나가 세션 전환을 걸칠 수 있어 줄에 박힌 경로를 따른다. */
    private void flush(List<Line> batch) throws IOException {
        int i = 0;
        while (i < batch.size()) {
            Path path = batch.get(i).path();
            StringBuilder sb = new StringBuilder();
            while (i < batch.size() && batch.get(i).path().equals(path)) {
                sb.append(batch.get(i++).json()).append('\n');
            }
            Files.createDirectories(path.getParent());              // 첫 쓰기에서 materialize
            Files.writeString(path, sb, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    /** 디스크에서 세션을 복원(파일 순서 = 시간순). parentUuid는 기록만 하고 복원엔 쓰지 않는다. */
    public static List<Message> load(Path path) throws IOException {
        if (!Files.exists(path)) return List.of();
        List<Message> messages = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            TranscriptEntry entry = Json.read(line, TranscriptEntry.class);
            messages.add(entry.message());
        }
        return messages;
    }

    @Override public void close() {
        queue.add(POISON);                                          // 잔여 flush 후 종료
        try { writer.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
