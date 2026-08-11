package agent.skill;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;

/**
 * {@code .agent/skills/<name>/SKILL.md} 디렉터리들을 스캔해 SkillCommand로 만든다.
 * 단순 파서이며 표준 frontmatter(LF, '---' 구분자)를 가정한다. 깨진 스킬은 건너뛰어
 * 나머지 로딩을 막지 않는다(부분 실패 격리).
 */
public final class SkillLoader {
    private SkillLoader() {}

    /** {@code .agent/skills/<name>/SKILL.md} 들을 로드. */
    public static List<SkillCommand> loadFrom(Path skillsDir) {
        if (!Files.isDirectory(skillsDir)) return List.of();
        List<SkillCommand> skills = new ArrayList<>();
        try (var dirs = Files.newDirectoryStream(skillsDir, Files::isDirectory)) {
            for (Path dir : dirs) {
                Path md = dir.resolve("SKILL.md");
                if (Files.isRegularFile(md)) parse(md, dir).ifPresent(skills::add);
            }
        } catch (IOException ignored) { /* 스캔 자체 실패 시 빈 결과 */ }
        return skills;
    }

    private static Optional<SkillCommand> parse(Path md, Path root) {
        try {
            // CRLF도 받아들이도록 LF로 정규화(비표준 파일도 최대한 살린다).
            String content = Files.readString(md).replace("\r\n", "\n");
            if (!content.startsWith("---")) return Optional.empty();

            // 여는 '---' 다음 줄부터 닫는 '---' 줄까지가 frontmatter.
            int end = content.indexOf("\n---", 3);
            if (end < 0) return Optional.empty();           // 닫는 구분자 없음 → 깨진 스킬

            String yaml = content.substring(3, end);
            Object loaded = new Yaml().load(yaml);
            Map<String, Object> fm = (loaded instanceof Map<?, ?> m) ? cast(m) : Map.of();

            String dirName = root.getFileName().toString();
            String name = String.valueOf(fm.getOrDefault("name", dirName));
            String desc = String.valueOf(fm.getOrDefault("description", ""));

            // 본문은 lazy: 스캔 시점엔 frontmatter만 읽고, 본문은 호출 시점에 디스크에서
            // 다시 읽는다(스킬 파일을 고치면 다음 호출에 바로 반영되는 보너스도 있다).
            // 로컬 스킬이므로 fromMcp=false.
            return Optional.of(new SkillCommand(name, desc, root, () -> readBody(md), false));
        } catch (Exception e) {
            return Optional.empty();   // 깨진 스킬은 건너뜀(전체 로딩을 막지 않음)
        }
    }

    /** 호출 시점 본문 로드: frontmatter 뒤의 본문만 잘라 낸다. */
    static String readBody(Path md) {
        try {
            String content = Files.readString(md).replace("\r\n", "\n");
            int end = content.indexOf("\n---", 3);
            if (end < 0) return "";
            // 닫는 "\n---" 뒤의 개행/EOF를 모두 흡수하고 본문만 strip.
            String afterMarker = content.substring(end + 4);   // "---" 직후
            int nl = afterMarker.indexOf('\n');
            return (nl < 0 ? "" : afterMarker.substring(nl + 1)).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);   // ToolExecutor가 도구 오류로 감싼다
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) { return (Map<String, Object>) m; }
}
