package agent.warmup;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 진짜 API 키 없이 {@link OpenAiChat}의 왕복 전체(요청 조립 → POST →
 * 상태 코드 검사 → choices[0].message.content 추출)를 검증한다.
 *
 * <p>실제 OpenAI 서버 대신 JDK 내장 {@link HttpServer}로 로컬 스텁을 띄우고,
 * 그 주소를 {@code OpenAiChat(apiKey, endpoint)}로 주입한다. 외부 의존성도,
 * 네트워크도, 비용도 없다.
 */
class OpenAiChatTest {

    private HttpServer server;
    private String endpoint;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 로컬 스텁 서버를 띄우고 엔드포인트 주소를 채운다. captured!=null이면 받은 요청 본문을 담는다. */
    private void startStub(int status, String responseBody, AtomicReference<String> captured)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] reqBytes = exchange.getRequestBody().readAllBytes();
            if (captured != null) {
                captured.set(new String(reqBytes, StandardCharsets.UTF_8));
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    @Test
    void extractsAssistantContentFromChoices() throws Exception {
        String response = """
                {
                  "id": "chatcmpl-test",
                  "choices": [
                    { "index": 0,
                      "message": { "role": "assistant", "content": "record는 불변 데이터를 담는 클래스다." },
                      "finish_reason": "stop" }
                  ],
                  "usage": { "prompt_tokens": 18, "completion_tokens": 15, "total_tokens": 33 }
                }
                """;
        AtomicReference<String> captured = new AtomicReference<>();
        startStub(200, response, captured);

        OpenAiChat client = new OpenAiChat("test-key", endpoint);
        String answer = client.chat(OpenAiChat.DEFAULT_MODEL, List.of(
                Map.of("role", "system", "content", "너는 간결하게 답하는 자바 도우미다."),
                Map.of("role", "user", "content", "record를 한 줄로 설명해줘")
        ));

        assertEquals("record는 불변 데이터를 담는 클래스다.", answer);
        // 요청 본문이 { model, messages:[...] } 모양으로 직렬화됐는지 확인
        String sent = captured.get();
        assertTrue(sent.contains("\"model\""), "요청에 model이 실려야 한다");
        assertTrue(sent.contains("\"messages\""), "요청에 messages가 실려야 한다");
        assertTrue(sent.contains("record를 한 줄로 설명해줘"), "user 프롬프트가 본문에 실려야 한다");
    }

    @Test
    void nonSuccessStatusThrowsWithBody() throws Exception {
        String errorBody = """
                { "error": { "message": "Incorrect API key provided", "code": "invalid_api_key" } }
                """;
        startStub(401, errorBody, null);

        OpenAiChat client = new OpenAiChat("bad-key", endpoint);
        IOException ex = assertThrows(IOException.class, () -> client.chat(
                OpenAiChat.DEFAULT_MODEL,
                List.of(Map.of("role", "user", "content", "안녕"))
        ));

        // 상태 코드와 응답 본문이 예외 메시지에 함께 드러나야 디버깅이 쉽다(2.6 함정 박스).
        assertTrue(ex.getMessage().contains("401"), "예외 메시지에 상태 코드가 있어야 한다");
        assertTrue(ex.getMessage().contains("invalid_api_key"), "예외 메시지에 응답 본문이 있어야 한다");
    }
}
