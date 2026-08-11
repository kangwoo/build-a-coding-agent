package agent.llm.openai;

import agent.exec.CancellationToken;
import agent.llm.*;
import agent.message.ContentBlock;
import agent.message.Json;
import agent.message.Message;
import agent.message.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class OpenAiClientTest {

    WireMockServer server;
    OpenAiClient client;

    @BeforeEach void setUp() {
        server = new WireMockServer(0);
        server.start();
        client = new OpenAiClient(
                new OpenAiConfig("test-key", "http://localhost:" + server.port()));
    }

    @AfterEach void tearDown() { server.stop(); }

    @Test
    void create_parses_text_usage_and_stop_reason() {
        // OpenAI Chat Completions 비스트리밍 응답
        String json = """
            {
              "choices": [
                {
                  "index": 0,
                  "message": {"role": "assistant", "content": "안녕 반가워"},
                  "finish_reason": "stop"
                }
              ],
              "usage": {"prompt_tokens": 12, "completion_tokens": 5}
            }
            """;

        server.stubFor(post("/v1/chat/completions")
                .willReturn(aResponse()
                        .withHeader("content-type", "application/json")
                        .withBody(json)));

        LlmRequest req = LlmRequest.chat("gpt-5.4-mini", "sys",
                List.of(Message.UserMessage.of("hi")));
        Message.AssistantMessage msg = client.create(req, CancellationToken.none());

        assertThat(msg.content()).hasSize(1);
        assertThat(msg.content().get(0))
                .isInstanceOfSatisfying(ContentBlock.TextBlock.class,
                        t -> assertThat(t.text()).isEqualTo("안녕 반가워"));   // content → TextBlock
        assertThat(msg.stopReason()).isEqualTo("end_turn");          // finish_reason "stop" → end_turn
        assertThat(msg.usage().inputTokens()).isEqualTo(12);         // prompt_tokens → input
        assertThat(msg.usage().outputTokens()).isEqualTo(5);         // completion_tokens → output
    }

    @Test
    void create_surfaces_http_errors() {
        server.stubFor(post("/v1/chat/completions")
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"bad key\"}")));

        LlmRequest req = LlmRequest.chat("gpt-5.4-mini", "sys",
                List.of(Message.UserMessage.of("hi")));

        Assertions.assertThrows(RuntimeException.class,
                () -> client.create(req, CancellationToken.none()));
    }

    @Test
    void request_serializes_tool_use_as_tool_calls_and_tool_result_as_tool_role() {
        // 응답 내용은 무관하다 — 우리가 보낸 "요청 본문"만 검증한다.
        server.stubFor(post("/v1/chat/completions")
                .willReturn(aResponse()
                        .withHeader("content-type", "application/json")
                        .withBody("""
                            {"choices":[{"index":0,
                              "message":{"role":"assistant","content":"ok"},
                              "finish_reason":"stop"}],
                             "usage":{"prompt_tokens":1,"completion_tokens":1}}
                            """)));

        // 도구 라운드트립 한 턴: user → assistant(tool_use) → user(tool_result).
        // 12장 미니 에이전트 루프가 다음 턴 요청을 만들 때의 대화 모양이다.
        ObjectNode grepInput = Json.MAPPER.createObjectNode();
        grepInput.put("pattern", ".");
        grepInput.put("outputMode", "FILES_WITH_MATCHES");

        List<Message> convo = List.of(
                Message.UserMessage.of("현재 디렉토리의 파일 목록을 보여줘"),
                Message.AssistantMessage.of(List.of(
                        new ContentBlock.TextBlock("목록을 확인하겠습니다."),
                        new ContentBlock.ToolUseBlock("call_abc123", "Grep", grepInput)),
                        Usage.EMPTY, "tool_use"),
                Message.UserMessage.ofBlocks(List.of(
                        ContentBlock.ToolResultBlock.ok("call_abc123",
                                "README.md\nbuild.gradle.kts"))));

        client.create(LlmRequest.chat("gpt-5.4-mini", "sys", convo), CancellationToken.none());

        // 실제로 전송된 요청 본문을 잡아 messages[]를 검증한다.
        var sent = server.findAll(postRequestedFor(urlEqualTo("/v1/chat/completions")));
        assertThat(sent).hasSize(1);
        JsonNode messages = Json.read(sent.get(0).getBodyAsString(), JsonNode.class).path("messages");
        // [0] system, [1] user, [2] assistant(tool_calls), [3] tool
        assertThat(messages).hasSize(4);

        // assistant의 tool_use → tool_calls[] (arguments는 객체가 아니라 JSON "문자열")
        JsonNode call = messages.path(2).path("tool_calls").path(0);
        assertThat(messages.path(2).path("role").asText()).isEqualTo("assistant");
        assertThat(call.path("id").asText()).isEqualTo("call_abc123");
        assertThat(call.path("type").asText()).isEqualTo("function");
        assertThat(call.path("function").path("name").asText()).isEqualTo("Grep");
        assertThat(call.path("function").path("arguments").isTextual()).isTrue();
        assertThat(call.path("function").path("arguments").asText()).contains("FILES_WITH_MATCHES");

        // tool_result → role:"tool" 메시지(tool_call_id 매칭). 도구 결과가 모델에 도달해야 한다.
        JsonNode tool = messages.path(3);
        assertThat(tool.path("role").asText()).isEqualTo("tool");
        assertThat(tool.path("tool_call_id").asText()).isEqualTo("call_abc123");
        assertThat(tool.path("content").asText()).contains("README.md");
    }
}
