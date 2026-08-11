package agent.llm.anthropic;

/**
 * Anthropic 설정. 5장 OpenAiConfig와 대칭이되 anthropic-version 헤더 값이 추가된다.
 * (OpenAI는 Bearer 토큰 하나면 되지만, Anthropic은 x-api-key + anthropic-version 두 헤더가 필요하다.)
 */
public record AnthropicConfig(String apiKey, String baseUrl, String anthropicVersion) {

    public static AnthropicConfig fromEnv() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("환경변수 ANTHROPIC_API_KEY 가 필요합니다.");
        }
        return new AnthropicConfig(key, "https://api.anthropic.com", "2023-06-01");
    }
}
