package agent.tool.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class FileStateCache {

    /** content는 줄끝 정규화(LF)된 내용. timestampMs는 floor된 mtime(ms). offset/limit은 어느 범위를 읽었는지. */
    public record FileState(String content, long timestampMs,
                            Integer offset, Integer limit, boolean partialView) {}

    private static final int MAX = 100;

    private final Map<Path, FileState> map = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {                       // accessOrder=true → LRU
            @Override protected boolean removeEldestEntry(Map.Entry<Path, FileState> e) {
                return size() > MAX;
            }
        });

    public Optional<FileState> get(Path path) { return Optional.ofNullable(map.get(path)); }
    public void set(Path path, FileState state) { map.put(path, state); }

    /**
     * 읽은 이후 디스크에서 *실제로* 바뀌었나?
     * mtime이 더 나중이어도 내용(정규화)이 같으면 가짜 변경(Windows·동기화의 mtime 튐)으로 보고 false.
     * Read 캐시는 줄 단위 재조립이라 끝 개행이 없으므로, 양쪽의 후행 개행을 지우고 비교한다.
     * (한계: 같은 ms 안의 변경은 mtime이 같아 못 잡는다 — 10.7 참고.)
     */
    public static boolean changedSinceRead(Path path, FileState state) throws IOException {
        if (Files.getLastModifiedTime(path).toMillis() <= state.timestampMs()) return false;
        String disk = Files.readString(path, StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace("\r", "\n");          // 비교는 줄끝 정규화로
        return !disk.replaceAll("\n+$", "")
                .equals(state.content().replaceAll("\n+$", ""));     // 후행 개행 정규화 후 비교
    }
}
