package agent.cli.bootstrap;

import agent.llm.LlmClient;
import agent.llm.openai.OpenAiClient;
import agent.llm.anthropic.AnthropicClient;
import agent.llm.gemini.GeminiClient;

public final class LlmClients {
    private LlmClients() {}

    public static LlmClient forProvider(String provider) {
        return switch (provider) {
            case "openai"    -> OpenAiClient.fromEnv();      // 5장에서 채움(기준)
            case "anthropic" -> AnthropicClient.fromEnv();   // ← 이 장에서 추가
            case "gemini"    -> GeminiClient.fromEnv();      // ← 이 장에서 추가
            default -> throw new IllegalArgumentException("알 수 없는 provider: " + provider);
        };
    }
}
