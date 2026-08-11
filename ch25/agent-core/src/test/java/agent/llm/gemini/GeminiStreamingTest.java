package agent.llm.gemini;

import agent.exec.CancellationToken;
import agent.llm.*;
import agent.message.Message;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class GeminiStreamingTest {

    WireMockServer server;
    GeminiClient client;

    @BeforeEach void setUp() {
        server = new WireMockServer(0);
        server.start();
        client = new GeminiClient(
                new GeminiConfig("test-key", "http://localhost:" + server.port()));
    }

    @AfterEach void tearDown() { server.stop(); }

    @Test
    void streams_text_and_assembles_final_message() {
        // Gemini SSE: data: {청크} 줄들. 각 청크는 candidates[0].content.parts[]를 담는다.
        String sse = """
            data: {"candidates":[{"content":{"role":"model","parts":[{"text":"안녕"}]}}]}

            data: {"candidates":[{"content":{"role":"model","parts":[{"text":" 반가워"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":5}}
            """;

        server.stubFor(post(urlPathMatching("/v1beta/models/.*:streamGenerateContent"))
                .willReturn(aResponse().withHeader("content-type", "text/event-stream").withBody(sse)));

        var acc = new AssistantMessageAccumulator();
        StringBuilder shown = new StringBuilder();

        LlmRequest req = LlmRequest.chat("gemini-x", "sys", List.of(Message.UserMessage.of("hi")));
        try (EventStream<StreamEvent> s = client.stream(req, CancellationToken.none())) {
            for (StreamEvent e : s) {
                acc.accept(e);
                if (e instanceof StreamEvent.BlockDelta bd
                        && bd.delta() instanceof StreamEvent.TextDelta td) {
                    shown.append(td.text());
                }
            }
        }

        assertThat(shown.toString()).isEqualTo("안녕 반가워");

        Message.AssistantMessage msg = acc.build();
        assertThat(msg.content()).hasSize(1);
        assertThat(msg.content().get(0))
                .isInstanceOfSatisfying(agent.message.ContentBlock.TextBlock.class,
                        t -> assertThat(t.text()).isEqualTo("안녕 반가워"));
        assertThat(msg.stopReason()).isEqualTo("end_turn");        // finishReason "STOP" → end_turn
        assertThat(msg.usage().inputTokens()).isEqualTo(12);       // usageMetadata.promptTokenCount
        assertThat(msg.usage().outputTokens()).isEqualTo(5);       // usageMetadata.candidatesTokenCount
    }

    @Test
    void function_call_part_assembles_tooluse_block() {
        // functionCall은 완성된 args 객체를 한 번에 준다 → 한 조각 InputJsonDelta로 흘려 누산.
        String sse = """
            data: {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"Read","args":{"path":"/a.txt"}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":3}}
            """;

        server.stubFor(post(urlPathMatching("/v1beta/models/.*:streamGenerateContent"))
                .willReturn(aResponse().withHeader("content-type", "text/event-stream").withBody(sse)));

        var acc = new AssistantMessageAccumulator();
        LlmRequest req = LlmRequest.chat("gemini-x", "sys", List.of(Message.UserMessage.of("hi")));
        try (EventStream<StreamEvent> s = client.stream(req, CancellationToken.none())) {
            for (StreamEvent e : s) acc.accept(e);
        }

        Message.AssistantMessage msg = acc.build();
        assertThat(msg.content()).hasSize(1);
        assertThat(msg.content().get(0))
                .isInstanceOfSatisfying(agent.message.ContentBlock.ToolUseBlock.class, u -> {
                    assertThat(u.name()).isEqualTo("Read");
                    assertThat(u.input().path("path").asText()).isEqualTo("/a.txt");  // 완성 JSON 한 조각이 끝에 한 번 파싱됨
                });
    }

    @Test
    void http_error_is_surfaced_as_apierror_event() {
        server.stubFor(post(urlPathMatching("/v1beta/models/.*:streamGenerateContent"))
                .willReturn(aResponse().withStatus(429).withBody("quota boom")));

        LlmRequest req = LlmRequest.chat("gemini-x", "sys", List.of(Message.UserMessage.of("hi")));

        List<StreamEvent> events = new ArrayList<>();
        try (EventStream<StreamEvent> s = client.stream(req, CancellationToken.none())) {
            for (StreamEvent e : s) events.add(e);
        }

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOfSatisfying(StreamEvent.ApiError.class, err -> {
            assertThat(err.httpStatus()).isEqualTo(429);
            assertThat(err.message()).contains("boom");
        });
    }
}
