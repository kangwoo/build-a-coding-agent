package agent.context;

import agent.llm.SystemBlock;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public final class SystemPromptBuilder {

    /** 정적/동적 분리 마커(렌더 시 캐시 경계로 쓰이고 문자열에선 제거). */
    public static final String DYNAMIC_BOUNDARY = " DYNAMIC_BOUNDARY ";

    private static final String IDENTITY =
            "You are a terminal coding agent. You help users with software engineering tasks.";
    private static final String BEHAVIOR =
            "Be concise and direct. Prefer using tools to inspect the project over guessing. "
            + "Confirm before destructive actions.";
    private static final String TOOLS_USAGE =
            "Use the provided tools to read, edit, and search files and to run shell commands. "
            + "Read a file before editing it.";

    /** 섹션들을 순서대로 조립해 문자열 배열로 반환(경계 마커 포함). */
    public List<String> build(Path cwd, String model) {
        List<PromptSection> sections = new ArrayList<>(List.of(
                PromptSection.staticSection("identity", IDENTITY),
                PromptSection.staticSection("behavior", BEHAVIOR),
                PromptSection.staticSection("tools", TOOLS_USAGE),
                // 경계
                PromptSection.staticSection("__boundary__", DYNAMIC_BOUNDARY),
                // 동적
                PromptSection.dynamic("env", () -> Optional.of(EnvInfo.detect(cwd, model).render()))
        ));

        List<String> out = new ArrayList<>();
        for (PromptSection s : sections) {
            s.compute().get().filter(t -> !t.isBlank()).ifPresent(out::add);   // 빈 섹션 제거
        }
        return out;
    }

    /** 경계를 살리는 provider용: 마커 앞은 정적, 뒤는 동적 SystemBlock 리스트(마커 자신은 뺀다). */
    public List<SystemBlock> blocks(Path cwd, String model) {
        List<String> parts = build(cwd, model);
        int boundary = parts.indexOf(DYNAMIC_BOUNDARY);
        List<SystemBlock> out = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            if (i == boundary) continue;
            out.add(i < boundary ? SystemBlock.staticBlock(parts.get(i))
                    : SystemBlock.dynamicBlock(parts.get(i)));
        }
        return out;
    }

    /** 한 문자열이 필요할 때(로그·테스트): 경계 마커 섹션을 통째로 빼고 합친다(빈 줄 안 남김). */
    public String render(Path cwd, String model) {
        return build(cwd, model).stream()
                .filter(s -> !s.equals(DYNAMIC_BOUNDARY))
                .collect(Collectors.joining("\n\n"))
                .strip();
    }
}
