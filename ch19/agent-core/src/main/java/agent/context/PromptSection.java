package agent.context;

import java.util.Optional;
import java.util.function.Supplier;

/** 시스템 프롬프트의 한 섹션. cacheBreak=true면 동적(캐시 경계 이후). */
public record PromptSection(String name, Supplier<Optional<String>> compute, boolean cacheBreak) {

    public static PromptSection staticSection(String name, String text) {
        return new PromptSection(name, () -> Optional.of(text), false);
    }
    public static PromptSection dynamic(String name, Supplier<Optional<String>> compute) {
        return new PromptSection(name, compute, true);
    }
}
