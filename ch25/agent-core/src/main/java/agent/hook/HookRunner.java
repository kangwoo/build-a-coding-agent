package agent.hook;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

public final class HookRunner {

    /** PreToolUse 집계 결과. */
    public record PreToolDecision(boolean denied, String message, List<String> addedContext) {}

    private final Map<HookEvent, List<HookMatcher>> config;
    private final Path cwd;
    private final BooleanSupplier trusted;     // 워크스페이스 신뢰 확인
    private final CommandHookExecutor executor = new CommandHookExecutor();

    public HookRunner(Map<HookEvent, List<HookMatcher>> config, Path cwd, BooleanSupplier trusted) {
        this.config = config; this.cwd = cwd; this.trusted = trusted;
    }

    public static HookRunner none() {
        return new HookRunner(Map.of(), Path.of("."), () -> true);
    }

    public PreToolDecision runPreToolUse(String toolName, JsonNode toolInput) {
        if (!trusted.getAsBoolean()) return new PreToolDecision(false, "", List.of());  // 신뢰 안 함 → 스킵

        List<HookCommand.Command> matched = matchedCommands(HookEvent.PRE_TOOL_USE, toolName);
        if (matched.isEmpty()) return new PreToolDecision(false, "", List.of());

        JsonNode input = buildInput(toolName, toolInput);
        List<HookResult> results = runParallel(matched, input);

        // 집계: 하나라도 차단/deny면 거부(deny > allow). ask는 16장 권한 시스템에 위임한다.
        boolean denied = results.stream().anyMatch(r ->
                r.outcome() == HookResult.Outcome.BLOCKING
                        || r.permissionDecision().filter("deny"::equals).isPresent());
        String msg = results.stream()
                .filter(r -> r.message().isPresent()).map(r -> r.message().get())
                .findFirst().orElse("훅이 도구를 차단함");
        List<String> ctx = results.stream()
                .flatMap(r -> r.additionalContext().stream()).toList();
        return new PreToolDecision(denied, msg, ctx);
    }

    private List<HookResult> runParallel(List<HookCommand.Command> hooks, JsonNode input) {
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HookResult>> futures = hooks.stream()
                    .map(h -> exec.submit(() -> executor.execute(h, input, cwd)))
                    .toList();
            List<HookResult> out = new ArrayList<>();
            for (var f : futures) out.add(f.get());
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<HookCommand.Command> matchedCommands(HookEvent event, String toolName) {
        return config.getOrDefault(event, List.of()).stream()
                .filter(m -> m.matches(toolName))
                .flatMap(m -> m.hooks().stream())
                .filter(h -> h instanceof HookCommand.Command)
                .map(h -> (HookCommand.Command) h)
                .toList();
    }

    private JsonNode buildInput(String toolName, JsonNode toolInput) {
        var node = agent.message.Json.MAPPER.createObjectNode();
        node.put("hook_event_name", "PreToolUse");
        node.put("tool_name", toolName);
        node.set("tool_input", toolInput);
        return node;
    }
}
