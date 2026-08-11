package agent.llm.openai;

import agent.exec.CancellationToken;
import agent.llm.*;
import agent.message.Message;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class OpenAiStreamingTest {

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
    void streams_text_and_assembles_final_message() {
        // OpenAI Chat Completions 스트림: data: {청크} 줄들 + data: [DONE]
        String sse = """
            data: {"choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"content":"안녕"},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"content":" 반가워"},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":5}}

            data: [DONE]
            """;

        server.stubFor(post("/v1/chat/completions")
                .willReturn(aResponse().withHeader("content-type", "text/event-stream").withBody(sse)));

        var acc = new AssistantMessageAccumulator();
        StringBuilder shown = new StringBuilder();

        LlmRequest req = LlmRequest.chat("gpt-5.4-mini", "sys",
                List.of(Message.UserMessage.of("hi")));
        try (EventStream<StreamEvent> s = client.stream(req, CancellationToken.none())) {
            for (StreamEvent e : s) {
                acc.accept(e);
                if (e instanceof StreamEvent.BlockDelta bd
                        && bd.delta() instanceof StreamEvent.TextDelta td) {
                    shown.append(td.text());
                }
            }
        }

        assertThat(shown.toString()).isEqualTo("안녕 반가워");      // 실시간 표시 누적

        Message.AssistantMessage msg = acc.build();
        assertThat(msg.content()).hasSize(1);
        assertThat(msg.content().get(0))
                .isInstanceOfSatisfying(agent.message.ContentBlock.TextBlock.class,
                        t -> assertThat(t.text()).isEqualTo("안녕 반가워"));  // 최종 메시지 조립
        assertThat(msg.stopReason()).isEqualTo("end_turn");        // finish_reason "stop" → end_turn
        assertThat(msg.usage().inputTokens()).isEqualTo(12);       // usage 청크의 prompt_tokens
        assertThat(msg.usage().outputTokens()).isEqualTo(5);       // usage 청크의 completion_tokens
    }

    @Test
    void http_error_is_surfaced_as_apierror_event() {
        // 4xx/5xx는 본문을 통째로 ApiError 한 개로 흘려보낸다(상태코드 보존).
        server.stubFor(post("/v1/chat/completions")
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));

        LlmRequest req = LlmRequest.chat("gpt-5.4-mini", "sys",
                List.of(Message.UserMessage.of("hi")));

        List<StreamEvent> events = new ArrayList<>();
        try (EventStream<StreamEvent> s = client.stream(req, CancellationToken.none())) {
            for (StreamEvent e : s) events.add(e);
        }

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOfSatisfying(StreamEvent.ApiError.class, err -> {
            assertThat(err.httpStatus()).isEqualTo(500);
            assertThat(err.message()).contains("boom");
        });
    }

    @Test
    void empty_stream_is_surfaced_as_apierror() {
        // HTTP 200이지만 블록도 finish_reason도 없이 [DONE]만 오는 스트림은 보통 프록시 실패다.
        String sse = "data: [DONE]\n";
        server.stubFor(post("/v1/chat/completions")
                .willReturn(aResponse().withHeader("content-type", "text/event-stream").withBody(sse)));

        LlmRequest req = LlmRequest.chat("gpt-5.4-mini", "sys",
                List.of(Message.UserMessage.of("hi")));

        boolean sawApiError = false;
        try (EventStream<StreamEvent> s = client.stream(req, CancellationToken.none())) {
            for (StreamEvent e : s) {
                if (e instanceof StreamEvent.ApiError) sawApiError = true;
            }
        }

        assertThat(sawApiError).isTrue();      // 빈 스트림 → ApiError 합성
    }
}
