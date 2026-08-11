package agent.tool;

import java.util.*;

public final class ToolRegistry {
    private final Map<String, Tool<?, ?>> byName = new LinkedHashMap<>();

    /** 등록 규칙: 정식 이름은 항상 키를 차지하고(같은 이름 재등록 = 교체), 별칭은 빈 키만 차지한다. */
    public ToolRegistry register(Tool<?, ?> tool) {
        byName.put(tool.name(), tool);                                        // 이름이 이긴다
        for (String alias : tool.aliases()) byName.putIfAbsent(alias, tool);  // 별칭은 양보한다
        return this;
    }

    public Optional<Tool<?, ?>> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<Tool<?, ?>> all() {
        // alias 중복 제거(같은 인스턴스가 여러 키에 등록될 수 있음)
        return new ArrayList<>(new LinkedHashSet<>(byName.values()));
    }
}
