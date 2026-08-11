package agent.skill;

import java.util.*;

/**
 * 이름→SkillCommand 맵. 모델이 호출하는 {@link SkillTool}이 참조하고,
 * 발견용 목록(이름+설명만)을 예산 내에서 만들어 system-reminder로 주입한다.
 */
public final class SkillRegistry {
    private final Map<String, SkillCommand> byName = new LinkedHashMap<>();

    public SkillRegistry(List<SkillCommand> skills) {
        for (SkillCommand s : skills) byName.put(s.name(), s);
    }

    public Optional<SkillCommand> find(String name) { return Optional.ofNullable(byName.get(name)); }
    public Collection<SkillCommand> all() { return byName.values(); }

    /** 발견용 목록(이름+설명만). 예산 내에서 잘라 system-reminder로 주입된다. */
    public String listing(int maxChars) {
        StringBuilder sb = new StringBuilder("<system-reminder>\n사용 가능한 스킬(필요 시 SkillTool로 호출):\n");
        for (SkillCommand s : byName.values()) {
            String line = "- " + s.name() + ": " + truncate(s.description(), 200) + "\n";
            if (sb.length() + line.length() > maxChars) break;     // 예산 초과 시 이후 항목 생략
            sb.append(line);
        }
        return sb.append("</system-reminder>").toString();
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
