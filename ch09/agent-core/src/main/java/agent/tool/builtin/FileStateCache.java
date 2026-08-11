package agent.tool.builtin;

import java.nio.file.Path;
import java.util.*;

public final class FileStateCache {

    /** timestampMs는 floor된 mtime(ms). offset/limit은 어느 범위를 읽었는지. */
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
}
