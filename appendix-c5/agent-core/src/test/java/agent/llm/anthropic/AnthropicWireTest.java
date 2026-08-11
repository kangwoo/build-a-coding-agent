package agent.llm.anthropic;

import agent.llm.LlmCapabilities;
import agent.llm.SystemBlock;
import agent.message.*;
import agent.message.ContentBlock.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicWireTest {

    private static final LlmCapabilities CAPS = new LlmCapabilities(true, true, true, true);

    @Test
    void tool_result_stays_inside_user_content_block() {
        // OpenAI는 role:"tool" 별도 메시지로 분리하지만 Anthropic은 user content 블록
        Message user = Message.UserMessage.ofBlocks(List.of(
                ToolResultBlock.ok("call_1", "파일 내용")));
        ArrayNode out = AnthropicWire.messages(List.of(user), CAPS);

        assertThat(out.get(0).path("role").asText()).isEqualTo("user");
        var block = out.get(0).path("content").get(0);
        assertThat(block.path("type").asText()).isEqualTo("tool_result");
        assertThat(block.path("tool_use_id").asText()).isEqualTo("call_1");
    }

    @Test
    void tool_use_becomes_assistant_content_block() {
        var input = Json.MAPPER.createObjectNode().put("path", "/a.txt");
        Message asst = Message.AssistantMessage.of(
                List.of(new ToolUseBlock("call_9", "Read", input)), Usage.EMPTY, "tool_use");
        ArrayNode out = AnthropicWire.messages(List.of(asst), CAPS);

        var block = out.get(0).path("content").get(0);
        assertThat(block.path("type").asText()).isEqualTo("tool_use");
        assertThat(block.path("name").asText()).isEqualTo("Read");
        assertThat(block.path("input").path("path").asText()).isEqualTo("/a.txt");
    }

    @Test
    void system_carries_exactly_one_cache_marker_when_caching_enabled() {
        ArrayNode sys = AnthropicWire.systemBlocks(List.of(
                SystemBlock.staticBlock("정적 규칙들…"), SystemBlock.dynamicBlock("<env>…")), true);
        assertThat(sys).hasSize(2);
        // 마커는 마지막 정적 블록에 1개 — 동적 블록은 캐시 대상이 아니다
        assertThat(sys.get(0).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
        assertThat(sys.get(1).has("cache_control")).isFalse();
    }

    @Test
    void system_carries_no_cache_marker_when_caching_disabled() {
        ArrayNode sys = AnthropicWire.systemBlocks(List.of(
                SystemBlock.staticBlock("정적 규칙들…"), SystemBlock.dynamicBlock("<env>…")), false);
        assertThat(sys).hasSize(2);
        assertThat(sys.get(0).has("cache_control")).isFalse();
        assertThat(sys.get(1).has("cache_control")).isFalse();
    }

    @Test
    void system_message_is_excluded_from_messages_array() {
        // 내부 알림(NoticeMessage)은 messages[]에 실리지 않는다(switch의 ignored 분기).
        Message sys = Message.NoticeMessage.of("내부 알림");
        Message user = Message.UserMessage.of("hi");
        ArrayNode out = AnthropicWire.messages(List.of(sys, user), CAPS);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).path("role").asText()).isEqualTo("user");
    }
}
