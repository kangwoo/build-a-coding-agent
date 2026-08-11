package agent.hook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HookConfigLoaderTest {

    @TempDir Path dir;

    @Test
    void settings_json_loads_into_event_map() throws Exception {
        Path settings = dir.resolve("settings.json");
        Files.writeString(settings, """
                { "hooks": { "PreToolUse": [
                    { "matcher": "Bash",
                      "hooks": [ { "type": "command", "command": "./audit.sh", "timeout": 10 } ] }
                ] } }
                """);

        var config = HookConfigLoader.load(settings);

        assertThat(config).containsOnlyKeys(HookEvent.PRE_TOOL_USE);   // "PreToolUse" → enum
        HookMatcher matcher = config.get(HookEvent.PRE_TOOL_USE).getFirst();
        assertThat(matcher.matches("Bash")).isTrue();
        assertThat(matcher.hooks())
                .containsExactly(new HookCommand.Command("./audit.sh", 10));
    }

    @Test
    void missing_or_broken_settings_mean_no_hooks() throws Exception {
        assertThat(HookConfigLoader.load(dir.resolve("없는파일.json"))).isEmpty();

        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{ 이건 JSON이 아니다");
        assertThat(HookConfigLoader.load(broken)).isEmpty();   // fail-open: 부팅을 막지 않는다
    }
}
