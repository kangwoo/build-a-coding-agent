package agent.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;

class SessionContextTest {

    @Test
    void sanitize_replaces_non_alnum_and_normalizes_nfc() {
        String s = SessionContext.sanitize(Path.of("/Users/x/My Proj"));
        assertThat(s).matches("[a-zA-Z0-9-]+");
        assertThat(s).doesNotContain("/").doesNotContain(" ");

        // 분해형(NFD) 악센트와 결합형(NFC)이 같은 결과로 정규화된다.
        String composed = "é";              // é (precomposed)
        String decomposed = "é";           // e + combining acute
        String a = SessionContext.sanitize(Path.of("/" + composed));
        String b = SessionContext.sanitize(Path.of("/" + decomposed));
        assertThat(a).isEqualTo(b);
        // 정규화 후 글자는 살아남는다 — NFC 기준 같은 이름이면 같은 디렉터리가 된다.
        assertThat(a).isEqualTo(SessionContext.sanitize(
                Path.of("/" + Normalizer.normalize(composed, Normalizer.Form.NFC))));

        // 한글 경로는 이름이 보존돼 서로 다른 프로젝트가 충돌하지 않는다(구분자만 '-').
        assertThat(SessionContext.sanitize(Path.of("/작업/프로젝트")))
                .isEqualTo("-작업-프로젝트")
                .isNotEqualTo(SessionContext.sanitize(Path.of("/작업/프로그램")));
    }

    @Test
    void switchSession_swaps_identity_atomically(@TempDir Path home) {
        System.setProperty("user.home", home.toString());
        var ctx = new SessionContext(Path.of("/proj/old"));
        Path before = ctx.transcriptPath();

        ctx.switchSession("session-123", Path.of("/proj/new"));
        Path after = ctx.transcriptPath();

        // 두 필드(sessionId·projectDir)가 한 번에 교체됐다.
        assertThat(after).isNotEqualTo(before);
        assertThat(after.toString()).endsWith("session-123.jsonl");
        assertThat(after.getParent().getFileName().toString())
                .isEqualTo(SessionContext.sanitize(Path.of("/proj/new")));
    }
}
