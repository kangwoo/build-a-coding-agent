package agent.llm;

/** provider별 기능 유무. 미지원 기능은 호출 측에서 no-op 처리. */
public record LlmCapabilities(
        boolean promptCaching,
        boolean thinkingBudget,
        boolean serverWebSearch,
        boolean structuredOutput) {}
