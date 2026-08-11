package agent.llm;

import agent.message.Message;
import java.util.List;

/**
 * provider 중립 요청. 지금은 채팅에 필요한 최소 필드만.
 * (system은 18장에서 List<SystemBlock>로, tools는 7장부터 채워진다.)
 */
public record LlmRequest(
        String model,
        String system,
        List<Message> messages,
        List<ToolSpec> tools,
        int maxTokens,
        ThinkingConfig thinking,
        Double temperature) {

    public static LlmRequest chat(String model, String system, List<Message> messages) {
        return new LlmRequest(model, system, messages, List.of(),
                4096, ThinkingConfig.disabled(), 1.0);
    }
}
