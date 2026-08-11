package agent.llm.gemini;

import agent.message.*;
import agent.message.ContentBlock.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiWireTest {

    @Test
    void text_block_becomes_text_part() {
        Message user = Message.UserMessage.of("안녕");
        ArrayNode out = GeminiWire.contents(List.of(user));

        assertThat(out.get(0).path("role").asText()).isEqualTo("user");
        assertThat(out.get(0).path("parts").get(0).path("text").asText()).isEqualTo("안녕");
    }

    @Test
    void assistant_role_maps_to_model_and_tooluse_to_functioncall() {
        var input = Json.MAPPER.createObjectNode().put("path", "/a.txt");
        Message asst = Message.AssistantMessage.of(
                List.of(new ToolUseBlock("call_9", "Read", input)), Usage.EMPTY, "tool_use");
        ArrayNode out = GeminiWire.contents(List.of(asst));

        assertThat(out.get(0).path("role").asText()).isEqualTo("model");
        var fc = out.get(0).path("parts").get(0).path("functionCall");
        assertThat(fc.path("name").asText()).isEqualTo("Read");
        assertThat(fc.path("args").path("path").asText()).isEqualTo("/a.txt");
    }

    @Test
    void tool_result_becomes_functionresponse_in_user_role() {
        // toolUseId는 GeminiClient가 합성한 call_1 — 직전 assistant의 tool_use에서 함수 이름을 복원해야 한다
        Message asst = Message.AssistantMessage.of(
                List.of(new ToolUseBlock("call_1", "Read", Json.MAPPER.createObjectNode())),
                Usage.EMPTY, "tool_use");
        Message user = Message.UserMessage.ofBlocks(List.of(
                ToolResultBlock.ok("call_1", "파일 내용")));
        ArrayNode out = GeminiWire.contents(List.of(asst, user));

        assertThat(out.get(1).path("role").asText()).isEqualTo("user");
        var fr = out.get(1).path("parts").get(0).path("functionResponse");
        assertThat(fr.path("name").asText()).isEqualTo("Read");   // 합성 id가 아니라 진짜 함수 이름
        assertThat(fr.path("response").path("content").asText()).isEqualTo("파일 내용");
    }

    @Test
    void system_becomes_system_instruction() {
        ObjectNode si = GeminiWire.systemInstruction("정적 규칙들");
        assertThat(si.path("parts").get(0).path("text").asText()).isEqualTo("정적 규칙들");
    }

    @Test
    void system_message_is_excluded_from_contents() {
        Message sys = Message.NoticeMessage.of("내부 알림");
        Message user = Message.UserMessage.of("hi");
        ArrayNode out = GeminiWire.contents(List.of(sys, user));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).path("role").asText()).isEqualTo("user");
    }
}
