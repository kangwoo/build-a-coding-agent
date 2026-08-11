package agent.subagent;

import agent.engine.*;          // AgentEngine, AgentServices, AgentEvent
import agent.exec.CancellationToken;
import agent.llm.*;             // LlmClient, EventStream
import agent.tool.*;            // ToolRegistry

import java.util.function.Function;

/** 12장 AgentEngine을 새로 만들어 서브 작업을 끝까지 돌리고 최종 텍스트만 모아 반환. */
public final class EngineSubagentRunner implements SubagentRunner {

    private final LlmClient model;
    private final String modelId;
    private final Function<AgentDefinition, ToolRegistry> toolPool;   // 종류별 도구 풀

    public EngineSubagentRunner(LlmClient model, String modelId,
                                Function<AgentDefinition, ToolRegistry> toolPool) {
        this.model = model; this.modelId = modelId; this.toolPool = toolPool;
    }

    @Override
    public String run(AgentDefinition def, String prompt, CancellationToken cancel) {
        // 격리: 새 엔진 = 새 메시지 배열. 부모 컨텍스트가 새지 않는다.
        // AgentServices.defaults() = 권한 allow-all·압축 off·영속성 off (격리된 하위 작업에 적합).
        AgentEngine sub = new AgentEngine(model, modelId, toolPool.apply(def),
                def.systemPrompt(), AgentServices.defaults());

        StringBuilder finalText = new StringBuilder();
        try (EventStream<AgentEvent> events = sub.submit(prompt, cancel)) {
            for (AgentEvent e : events) {
                if (e instanceof AgentEvent.AssistantTextDelta d) finalText.append(d.text());
                // 도구를 부른 턴의 텍스트는 진행 서술이지 결과가 아니다 — 비우고 최종 턴 텍스트만 남긴다.
                // 도구 시작/완료(ToolStarted/Finished) 자체는 부모에 노출하지 않는다(격리).
                if (e instanceof AgentEvent.ToolStarted) finalText.setLength(0);
            }
        } catch (StreamCancelledException e) {
            // 소비 스레드가 인터럽트돼도 그때까지 모은 부분 텍스트는 버리지 않는다(이미 지불한 토큰).
            // 완결로 위장하지 않도록 잘렸다는 표시를 앞에 붙인다 — 6장 '정상 종료 위장 금지'의 소비자 측 몫.
            return "[중단됨 — 아래는 부분 결과]\n" + finalText.toString().strip();
        }
        return finalText.toString().strip();
    }
}
