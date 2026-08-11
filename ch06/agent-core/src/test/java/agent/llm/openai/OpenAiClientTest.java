package agent.llm.openai;

import agent.exec.CancellationToken;
import agent.llm.*;
import agent.message.ContentBlock;
import agent.message.Message;
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
}
