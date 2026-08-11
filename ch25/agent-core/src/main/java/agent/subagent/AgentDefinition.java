package agent.subagent;

import java.util.Set;

/** 서브에이전트의 "종류"를 정의한다. 종류마다 시스템 프롬프트와 쓸 수 있는 도구가 다르다. */
public record AgentDefinition(String type, String systemPrompt, Set<String> allowedTools) {

    public static final AgentDefinition GENERAL = new AgentDefinition(
            "general",
            "You are a focused sub-agent. Complete the delegated task and return a concise summary.",
            Set.of("Read", "Glob", "Grep"));   // 위임 작업엔 보통 읽기·탐색만(Bash는 15장에서 추가)
}
