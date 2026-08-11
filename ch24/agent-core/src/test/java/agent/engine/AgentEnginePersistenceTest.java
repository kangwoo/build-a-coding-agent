package agent.engine;

import agent.exec.CancellationToken;
import agent.message.ContentBlock.TextBlock;
import agent.message.Message;
import agent.message.Usage;
import agent.session.SessionContext;
import agent.session.TranscriptStore;
import agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** resume()와 transcript record() 이음매를 LLM 호출 없이(가짜 모델) 검증한다. */
class AgentEnginePersistenceTest {

    @Test
    void resume_seeds_engine_messages_in_order() {
        var user = Message.UserMessage.of("이전 질문");
        var assistant = Message.AssistantMessage.of(
                List.of(new TextBlock("이전 답변")), Usage.EMPTY, "end_turn");

        var engine = new AgentEngine(new AgentEngineTest.FakeModel(List.of()), "m",
                new ToolRegistry(), "sys", AgentServices.defaults());
        engine.resume(List.of(user, assistant));

        assertThat(engine.messages()).containsExactly(user, assistant);
    }

    @Test
    void engine_records_user_and_assistant_when_transcript_present(@TempDir Path home) throws Exception {
        System.setProperty("user.home", home.toString());
        var session = new SessionContext(home.resolve("proj"));

        // 도구 없는 한 턴: assistant가 텍스트만 내고 end_turn.
        var turn = Message.AssistantMessage.of(
                List.of(new TextBlock("안녕하세요")), Usage.EMPTY, "end_turn");

        try (var transcript = new TranscriptStore(session)) {
            var services = new AgentServices(
                    agent.permission.PermissionGate.allowAll(),
                    agent.hook.HookRunner.none(), null, null, transcript);
            var engine = new AgentEngine(new AgentEngineTest.FakeModel(List.of(turn)), "m",
                    new ToolRegistry(), "sys", services);

            try (var s = engine.submit("핑", CancellationToken.none())) {
                s.forEach(e -> {});   // 루프를 끝까지 돌린다
            }
        }   // close → flush

        List<Message> persisted = TranscriptStore.load(session.transcriptPath());
        // 첫 줄은 18장이 주입한 메타 컨텍스트(<system-reminder>), 그 뒤로 여는 프롬프트와 응답이 이어진다.
        // 정확히: [메타 컨텍스트, user "핑", assistant 응답] 세 줄.
        assertThat(persisted).hasSize(3);
        assertThat(persisted.get(1).content().get(0)).isEqualTo(new TextBlock("핑"));
        assertThat(persisted.get(2)).isInstanceOf(Message.AssistantMessage.class);
        // 여는 user 프롬프트가 실제로 영속화됐는지(record 호출 지점 검증).
        assertThat(persisted).anyMatch(m -> m instanceof Message.UserMessage
                && m.content().get(0).equals(new TextBlock("핑")));
    }
}
