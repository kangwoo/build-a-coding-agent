package agent.llm;

import agent.message.Message;
import java.util.List;
import java.util.stream.Collectors;

/**
 * provider 중립 요청. 지금은 채팅에 필요한 최소 필드만.
 * (system은 18장부터 List<SystemBlock> — 정적/동적 경계가 요청까지 실려 온다. tools는 7장부터 채워진다.)
 */
public record LlmRequest(
        String model,
        List<SystemBlock> system,
        List<Message> messages,
        List<ToolSpec> tools,
        int maxTokens,
        ThinkingConfig thinking,
        Double temperature) {

    public static LlmRequest chat(String model, String system, List<Message> messages) {
        return new LlmRequest(model, systemBlocks(system), messages, List.of(),
                4096, ThinkingConfig.disabled(), 1.0);
    }

    /** String 편의 변환 — null/공백이면 빈 리스트, 아니면 정적 한 블록. */
    public static List<SystemBlock> systemBlocks(String system) {
        return (system == null || system.isBlank())
                ? List.of() : List.of(SystemBlock.staticBlock(system));
    }

    /** 경계를 모르는 provider용 — 블록 텍스트를 정적 → 동적 순서 그대로 한 문자열로 잇는다. */
    public String systemText() {
        return system.stream().map(SystemBlock::text).collect(Collectors.joining("\n\n"));
    }
}
