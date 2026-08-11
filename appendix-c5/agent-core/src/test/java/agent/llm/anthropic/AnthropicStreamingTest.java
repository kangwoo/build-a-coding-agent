package agent.llm.anthropic;

import agent.exec.CancellationToken;
import agent.llm.*;
import agent.message.Message;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class AnthropicStreamingTest {

    WireMockServer server;
    AnthropicClient client;

    @BeforeEach void setUp() {
        server = new WireMockServer(0);
        server.start();
        client = new AnthropicClient(
                new AnthropicConfig("test-key", "http://localhost:" + server.port(), "2023-06-01"));
    }

    @AfterEach void tearDown() { server.stop(); }

    @Test
    void streams_text_and_assembles_final_message() {
        // Anthropic SSE: 이벤트 타입이 명시된 줄들. message_start … message_stop.
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":12,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"안녕"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

            event: message_stop
            data: {"type":"message_stop"}
            """;

        server.stubFor(post("/v1/messages")
                .willReturn(aResponse().withHeader("content-type", "text/event-stream").withBody(sse)));

        var acc = new AssistantMessageAccumulator();
        StringBuilder shown = new StringBuilder();

        LlmRequest req = LlmRequest.chat("claude-x", "sys",
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

        assertThat(shown.toString()).isEqualTo("안녕");           // 실시간 표시 누적

        Message.AssistantMessage msg = acc.build();
        assertThat(msg.content()).hasSize(1);
        assertThat(msg.content().get(0))
                .isInstanceOfSatisfying(agent.message.ContentBlock.TextBlock.class,
                        t -> assertThat(t.text()).isEqualTo("안녕"));  // 최종 메시지 조립
        assertThat(msg.stopReason()).isEqualTo("end_turn");
        assertThat(msg.usage().inputTokens()).isEqualTo(12);      // message_start의 input_tokens 보존
        assertThat(msg.usage().outputTokens()).isEqualTo(5);      // message_delta의 output_tokens
    }

    @Test
    void tool_use_stream_assembles_tooluse_block() {
        // content_block_start(type:tool_use) + input_json_delta 조각들 → ToolUseBlock(id,name,input)
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":7}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tu_1","name":"Read"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\\"/a.txt\\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":9}}

            event: message_stop
            data: {"type":"message_stop"}
            """;

        server.stubFor(post("/v1/messages")
                .willReturn(aResponse().withHeader("content-type", "text/event-stream").withBody(sse)));

        var acc = new AssistantMessageAccumulator();
        LlmRequest req = LlmRequest.chat("claude-x", "sys", List.of(Message.UserMessage.of("hi")));
        try (EventStream<StreamEvent> s = client.stream(req, CancellationToken.none())) {
            for (StreamEvent e : s) acc.accept(e);
        }

        Message.AssistantMessage msg = acc.build();
        assertThat(msg.stopReason()).isEqualTo("tool_use");
        assertThat(msg.content()).hasSize(1);
        assertThat(msg.content().get(0))
                .isInstanceOfSatisfying(agent.message.ContentBlock.ToolUseBlock.class, u -> {
                    assertThat(u.id()).isEqualTo("tu_1");
                    assertThat(u.name()).isEqualTo("Read");
                    // 부분 JSON은 끝에 한 번만 파싱된다(누산기 불변식).
                    assertThat(u.input().path("path").asText()).isEqualTo("/a.txt");
                });
    }

    @Test
    void http_error_is_surfaced_as_apierror_event() {
        // 4xx/5xx는 본문을 통째로 ApiError 한 개로 흘려보낸다(상태코드 보존).
        server.stubFor(post("/v1/messages")
                .willReturn(aResponse().withStatus(529).withBody("overloaded boom")));

        LlmRequest req = LlmRequest.chat("claude-x", "sys", List.of(Message.UserMessage.of("hi")));

        List<StreamEvent> events = new ArrayList<>();
        try (EventStream<StreamEvent> s = client.stream(req, CancellationToken.none())) {
            for (StreamEvent e : s) events.add(e);
        }

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOfSatisfying(StreamEvent.ApiError.class, err -> {
            assertThat(err.httpStatus()).isEqualTo(529);
            assertThat(err.message()).contains("boom");
        });
    }

    @Test
    void sse_error_event_is_surfaced_with_zero_status() {
        // SSE 본문 내 type:error 이벤트는 HTTP 자체가 200이라 httpStatus=0으로 흘린다.
        String sse = """
            event: error
            data: {"type":"error","error":{"type":"overloaded_error","message":"잠시 후 다시"}}
            """;
        server.stubFor(post("/v1/messages")
                .willReturn(aResponse().withHeader("content-type", "text/event-stream").withBody(sse)));

        LlmRequest req = LlmRequest.chat("claude-x", "sys", List.of(Message.UserMessage.of("hi")));

        List<StreamEvent> events = new ArrayList<>();
        try (EventStream<StreamEvent> s = client.stream(req, CancellationToken.none())) {
            for (StreamEvent e : s) events.add(e);
        }

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOfSatisfying(StreamEvent.ApiError.class, err -> {
            assertThat(err.httpStatus()).isEqualTo(0);
            assertThat(err.message()).contains("잠시 후");
        });
    }
}
