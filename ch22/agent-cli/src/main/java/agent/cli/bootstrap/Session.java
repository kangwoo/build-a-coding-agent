package agent.cli.bootstrap;

import java.nio.file.Path;
import java.util.UUID;

/** 이번 실행의 세션 컨텍스트. 20장에서 영속화·복원이 붙는다. */
public record Session(String sessionId, Path workingDir) {
    public static Session create() {
        return new Session(UUID.randomUUID().toString(), Path.of("").toAbsolutePath());
    }
}
