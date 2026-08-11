package agent.cli.bootstrap;

import agent.llm.LlmClient;
import agent.llm.openai.OpenAiClient;

public final class LlmClients {
    private LlmClients() {}

    public static LlmClient forProvider(String provider) {
        return switch (provider) {
            case "openai" -> OpenAiClient.fromEnv();
            case "anthropic", "gemini" ->
                throw new UnsupportedOperationException(provider + " 구현은 24장에서 합류합니다.");
            default -> throw new IllegalArgumentException("알 수 없는 provider: " + provider);
        };
    }
}
