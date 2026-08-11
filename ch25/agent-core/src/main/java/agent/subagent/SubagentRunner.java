package agent.subagent;

import agent.exec.CancellationToken;

/** 하위 에이전트를 격리 실행하고 최종 답변(요약)을 반환. */
public interface SubagentRunner {
    String run(AgentDefinition def, String prompt, CancellationToken cancel);
}
