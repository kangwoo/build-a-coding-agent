package agent.cli.bootstrap;

import agent.message.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import org.jline.reader.LineReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 워크스페이스 신뢰 장부. 처음 보는 워크스페이스면 1회 묻고 답을 기억한다.
 * 장부는 워크스페이스 "밖"(~/.agent/trusted.json)에 둔다 —
 * 저장소 안에 두면 남의 저장소가 신뢰 마커를 심어 스스로를 신뢰시킬 수 있다.
 */
public final class WorkspaceTrust {
    private final Path ledger;
    private final LineReader reader;

    public WorkspaceTrust(LineReader reader) {
        this(Path.of(System.getProperty("user.home"), ".agent", "trusted.json"), reader);
    }

    WorkspaceTrust(Path ledger, LineReader reader) { this.ledger = ledger; this.reader = reader; }

    /** 장부에 있으면 그 답을, 없으면 묻고 기억한다. 답을 되돌리려면 장부 파일에서 지운다. */
    public boolean confirm(Path workspace) {
        String key = workspace.toAbsolutePath().normalize().toString();
        Map<String, Boolean> known = load();
        Boolean saved = known.get(key);
        if (saved != null) return saved;

        String line;
        try {
            line = reader.readLine("⚠ 이 워크스페이스의 훅(.agent/settings.json) 실행을 신뢰할까요? [y/N]: ");
        } catch (RuntimeException e) {
            return false;   // 입력 불가(비대화형 등)면 저장 없이 거부 — 다음에 다시 묻는다(fail-closed)
        }
        boolean yes = line != null && line.strip().equalsIgnoreCase("y");
        known.put(key, yes);
        save(known);
        return yes;
    }

    private Map<String, Boolean> load() {
        try {
            if (!Files.isRegularFile(ledger)) return new LinkedHashMap<>();
            return Json.MAPPER.readValue(Files.readString(ledger),
                    new TypeReference<LinkedHashMap<String, Boolean>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();   // 깨진 장부는 빈 장부 — 다시 물으면 된다
        }
    }

    private void save(Map<String, Boolean> known) {
        try {
            Files.createDirectories(ledger.getParent());
            Files.writeString(ledger, Json.write(known));
        } catch (Exception e) { /* 저장 실패는 치명적이지 않다 — 다음 시작 때 다시 묻는다 */ }
    }
}
