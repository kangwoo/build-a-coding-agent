package agent.llm.openai;

public record OpenAiConfig(String apiKey, String baseUrl) {

    public static OpenAiConfig fromEnv() {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("환경변수 OPENAI_API_KEY 가 필요합니다.");
        }
        return new OpenAiConfig(key, "https://api.openai.com");
    }
}
