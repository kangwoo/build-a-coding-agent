package agent.warmup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 워밍업용(=warm-up spike) OpenAI Chat Completions 클라이언트.
 * 동기·비스트리밍·String 반환의 최소 구현. 5장의 진짜 LlmClient로 승격된다.
 */
public class OpenAiChat {

    /** 기본 모델 id. (OpenAI 최신 id로 교체 가능) */
    public static final String DEFAULT_MODEL = "gpt-5.4-mini";

    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private final HttpClient http;
    private final ObjectMapper json;
    private final String apiKey;
    private final String endpoint;

    public OpenAiChat(String apiKey) {
        this(apiKey, DEFAULT_ENDPOINT);
    }

    /** 엔드포인트를 지정하는 생성자 — 2.4절 테스트에서 로컬 스텁 주소를 주입할 때 쓴다. */
    public OpenAiChat(String apiKey, String endpoint) {
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.http = HttpClient.newBuilder()                  // 기본값은 무한 대기라
                .connectTimeout(Duration.ofSeconds(30))      // 연결 수립에 상한을 둔다
                .build();
        this.json = new ObjectMapper();
    }

    /**
     * 메시지 배열을 보내고 모델의 응답(텍스트)을 받는다.
     *
     * @param model    모델 id (예: {@link #DEFAULT_MODEL})
     * @param messages {"role":..,"content":..} 형태의 메시지 목록
     * @return choices[0].message.content
     */
    public String chat(String model, List<Map<String, String>> messages)
            throws IOException, InterruptedException {

        // 1) 요청 본문 JSON 만들기: { "model":..., "messages":[...] }
        Map<String, Object> payload = Map.of(
                "model", model,
                "messages", messages
        );
        String requestBody = json.writeValueAsString(payload);

        // 2) POST 요청 구성
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(120))            // 요청 한 건 전체의 상한
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        // 3) 동기 전송 (응답이 다 올 때까지 블로킹)
        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

        // 4) 상태 코드 확인 — 2xx가 아니면 본문째 예외로
        if (response.statusCode() / 100 != 2) {
            throw new IOException(
                    "OpenAI API 오류: HTTP " + response.statusCode()
                            + " — " + response.body());
        }

        // 5) 응답 JSON에서 choices[0].message.content 만 추출
        JsonNode root = json.readTree(response.body());
        return root.path("choices").path(0)
                .path("message").path("content")
                .asText();
    }
}
