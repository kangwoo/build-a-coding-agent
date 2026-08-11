package agent.session;

import agent.message.ContentBlock;
import agent.message.Json;
import agent.message.Message;
import agent.message.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptStoreTest {

    @Test
    void roundtrip_persists_and_restores(@TempDir Path home) throws Exception {
        System.setProperty("user.home", home.toString());
        var session = new SessionContext(home.resolve("proj"));

        try (var store = new TranscriptStore(session)) {
            store.record(Message.UserMessage.of("hello"));
            store.record(Message.AssistantMessage.of(
                    List.of(new ContentBlock.TextBlock("hi there")),
                    Usage.EMPTY, "end_turn"));
        }   // close → flush

        List<Message> restored = TranscriptStore.load(session.transcriptPath());
        assertThat(restored).hasSize(2);
        assertThat(restored.get(0)).isInstanceOf(Message.UserMessage.class);
        assertThat(restored.get(1)).isInstanceOf(Message.AssistantMessage.class);
        assertThat(restored.get(0).content().get(0))
                .isEqualTo(new ContentBlock.TextBlock("hello"));
    }

    @Test
    void dedup_skips_already_recorded(@TempDir Path home) throws Exception {
        System.setProperty("user.home", home.toString());
        var session = new SessionContext(home.resolve("proj"));

        Message m = Message.UserMessage.of("dup");
        try (var store = new TranscriptStore(session)) {
            store.record(m);
            store.record(m);   // 같은 uuid 두 번 — 한 줄만 남아야 한다
        }

        assertThat(TranscriptStore.load(session.transcriptPath())).hasSize(1);
    }

    @Test
    void materialize_only_on_first_record(@TempDir Path home) throws Exception {
        System.setProperty("user.home", home.toString());
        var session = new SessionContext(home.resolve("proj"));

        try (var store = new TranscriptStore(session)) {
            // record() 없이 바로 닫는다 — 빈 세션은 파일을 만들면 안 된다.
        }

        assertThat(Files.exists(session.transcriptPath())).isFalse();
    }

    @Test
    void parentUuid_chains_in_order_and_restarts_on_session_switch(@TempDir Path home) throws Exception {
        System.setProperty("user.home", home.toString());
        var session = new SessionContext(home.resolve("proj"));
        Path firstPath = session.transcriptPath();

        Message m0 = Message.UserMessage.of("first");
        Message m1 = Message.AssistantMessage.of(
                List.of(new ContentBlock.TextBlock("second")), Usage.EMPTY, "end_turn");
        Message m2 = Message.UserMessage.of("third");
        try (var store = new TranscriptStore(session)) {
            store.record(m0);
            store.record(m1);
            session.switchSession("switched", home.resolve("proj"));   // 드레인 전에 전환
            store.record(m2);
        }

        // 전환 전 줄은 이전 세션 파일에, 전환 후 줄은 새 세션 파일에 남는다.
        List<String> before = Files.readAllLines(firstPath);
        List<String> after = Files.readAllLines(session.transcriptPath());
        assertThat(before).hasSize(2);
        assertThat(after).hasSize(1);

        TranscriptEntry e0 = Json.read(before.get(0), TranscriptEntry.class);
        TranscriptEntry e1 = Json.read(before.get(1), TranscriptEntry.class);
        TranscriptEntry e2 = Json.read(after.get(0), TranscriptEntry.class);

        assertThat(e0.parentUuid()).isNull();                  // 첫 줄은 부모 없음(NON_NULL로 생략)
        assertThat(e1.parentUuid()).isEqualTo(m0.uuid());
        assertThat(e2.parentUuid()).isNull();                  // 새 세션은 체인도 새로 시작
        assertThat(e2.sessionId()).isEqualTo("switched");
    }

    @Test
    void load_returns_empty_for_missing_file(@TempDir Path home) throws Exception {
        var session = new SessionContext(home.resolve("proj"));
        assertThat(TranscriptStore.load(session.transcriptPath())).isEmpty();
    }
}
