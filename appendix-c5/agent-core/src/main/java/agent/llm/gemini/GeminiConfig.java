package agent.llm.gemini;

/**
 * Gemini 설정. API 키는 자체 헤더(x-goog-api-key)로 전달한다(OpenAI의 Authorization: Bearer와 다르다).
 */
public record GeminiConfig(String apiKey, String baseUrl) {

    public static GeminiConfig fromEnv() {
        String key = System.getenv("GEMINI_API_KEY");
        if (key == null || key.isBlank()) key = System.getenv("GOOGLE_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("환경변수 GEMINI_API_KEY(또는 GOOGLE_API_KEY)가 필요합니다.");
        }
        return new GeminiConfig(key, "https://generativelanguage.googleapis.com");
    }
}
