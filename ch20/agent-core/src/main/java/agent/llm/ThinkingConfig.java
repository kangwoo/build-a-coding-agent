package agent.llm;

/** 추론(thinking) 설정. OpenAI는 reasoning_effort로(5장), Anthropic은 budget_tokens로(24장) 매핑한다. */
public sealed interface ThinkingConfig
        permits ThinkingConfig.Disabled, ThinkingConfig.Enabled {

    static ThinkingConfig disabled() { return new Disabled(); }
    static ThinkingConfig enabled(int budgetTokens) { return new Enabled(budgetTokens); }

    record Disabled() implements ThinkingConfig {}
    record Enabled(int budgetTokens) implements ThinkingConfig {}
}
