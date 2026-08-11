package agent.message;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageSerializationTest {

    @Test
    void user_message_roundtrips() {
        Message original = Message.UserMessage.of("이 파일 읽어줘");

        String json = Json.write(original);
        Message back = Json.read(json, Message.class);

        assertThat(json).contains("\"role\":\"user\"");   // 판별자 자동 삽입
        assertThat(back).isEqualTo(original);             // record equals = 값 동등성
    }

    @Test
    void assistant_with_tool_use_roundtrips() {
        ObjectNode input = Json.MAPPER.createObjectNode().put("path", "/tmp/a.txt");
        var toolUse = new ContentBlock.ToolUseBlock("tu_1", "Read", input);
        Message original = Message.AssistantMessage.of(
                List.of(new ContentBlock.TextBlock("읽어볼게요"), toolUse),
                new Usage(10, 5, 0, 0, 0),
                "tool_use");

        Message back = Json.read(Json.write(original), Message.class);

        assertThat(back).isInstanceOf(Message.AssistantMessage.class);
        var am = (Message.AssistantMessage) back;
        assertThat(am.content()).hasSize(2);
        assertThat(am.content().get(1)).isInstanceOf(ContentBlock.ToolUseBlock.class);
        assertThat(((ContentBlock.ToolUseBlock) am.content().get(1)).input().get("path").asText())
                .isEqualTo("/tmp/a.txt");
    }

    @Test
    void content_block_type_discriminator_is_anthropic_compatible() {
        // LLM wire 규약과 같은 type 값을 쓰는지 확인
        assertThat(Json.write(new ContentBlock.TextBlock("hi"))).contains("\"type\":\"text\"");
        assertThat(Json.write(ContentBlock.ToolResultBlock.ok("tu_1", "done")))
                .contains("\"type\":\"tool_result\"");
    }

    @Test
    void usage_merges_cumulatively_and_ignores_zeros() {
        Usage u = new Usage(100, 0, 0, 0, 0);
        // 다음 스냅샷: output만 갱신되고 input은 0(=값 없음)으로 도착
        Usage merged = u.mergeCumulative(new Usage(0, 42, 0, 0, 0));

        assertThat(merged.inputTokens()).isEqualTo(100);  // 0이 덮어쓰지 않음
        assertThat(merged.outputTokens()).isEqualTo(42);
    }
}
