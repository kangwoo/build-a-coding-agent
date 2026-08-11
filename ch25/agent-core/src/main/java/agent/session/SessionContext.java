package agent.session;

import java.nio.file.*;
import java.text.Normalizer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 세션 정체성(sessionId+projectDir)을 한 덩어리로 원자적으로 다룬다.
 * 두 필드를 따로 바꾸면 전환 중간에 transcriptPath()가 엉뚱한 곳을 가리킬 수 있어,
 * Identity record를 AtomicReference로 통째 교체한다.
 */
public final class SessionContext {

    /** sessionId와 projectDir는 한 덩어리로만 바뀐다. */
    public record Identity(String sessionId, Path projectDir) {}

    private final AtomicReference<Identity> identity;
    private final Path agentHome;

    public SessionContext(Path projectDir) {
        this.identity = new AtomicReference<>(new Identity(UUID.randomUUID().toString(), projectDir));
        this.agentHome = Path.of(System.getProperty("user.home"), ".agent");
    }

    public Identity identity() { return identity.get(); }

    /** 세션 전환(원자적). 두 필드를 따로 set하면 안 된다. */
    public void switchSession(String sessionId, Path projectDir) {
        identity.set(new Identity(sessionId, projectDir));
    }

    public Path transcriptPath() { return transcriptPath(identity.get()); }

    /** 호출자가 샘플링해 둔 정체성으로 경로를 확정한다(줄에 박는 sessionId와 경로를 같은 순간으로 묶을 때). */
    public Path transcriptPath(Identity id) {
        return agentHome.resolve("projects").resolve(sanitize(id.projectDir()))
                .resolve(id.sessionId() + ".jsonl");
    }

    static String sanitize(Path p) {
        String nfc = Normalizer.normalize(p.toString(), Normalizer.Form.NFC);
        // 유니코드 글자·숫자는 보존한다 — ASCII 영숫자만 남기면 한글 경로가 전부 '-'로 붕괴해 프로젝트끼리 충돌한다
        return nfc.replaceAll("[^\\p{L}\\p{N}]", "-");
    }
}
