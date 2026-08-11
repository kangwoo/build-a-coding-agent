package agent.hook;

import agent.message.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** {@code .agent/settings.json}의 {@code "hooks"}를 훅 설정으로 읽는다. */
public final class HookConfigLoader {
    private HookConfigLoader() {}

    /** JSON 키("PreToolUse") ↔ enum(PRE_TOOL_USE) 대응표. 모르는 키는 무시된다. */
    private static final Map<String, HookEvent> EVENT_KEYS = Map.of(
            "PreToolUse", HookEvent.PRE_TOOL_USE, "PostToolUse", HookEvent.POST_TOOL_USE,
            "UserPromptSubmit", HookEvent.USER_PROMPT_SUBMIT, "Stop", HookEvent.STOP,
            "SessionStart", HookEvent.SESSION_START, "SessionEnd", HookEvent.SESSION_END);

    /** 파일이 없거나 JSON이 깨졌으면 빈 설정 — 훅 설정이 부팅을 막지 않는다(fail-open). */
    public static Map<HookEvent, List<HookMatcher>> load(Path settingsFile) {
        if (!Files.isRegularFile(settingsFile)) return Map.of();
        try {
            JsonNode hooks = Json.MAPPER.readTree(Files.readString(settingsFile)).path("hooks");
            Map<HookEvent, List<HookMatcher>> config = new EnumMap<>(HookEvent.class);
            for (var e : EVENT_KEYS.entrySet()) {
                JsonNode matchers = hooks.path(e.getKey());
                if (matchers.isArray()) config.put(e.getValue(), Json.MAPPER.convertValue(
                        matchers, new TypeReference<List<HookMatcher>>() {}));
            }
            return config;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
