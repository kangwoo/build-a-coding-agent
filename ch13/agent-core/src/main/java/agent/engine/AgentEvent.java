package agent.engine;

import com.fasterxml.jackson.databind.JsonNode;

/** 루프가 REPL로 흘리는 이벤트. 화면 표현은 REPL이 정한다(엔진은 화면을 모른다). */
public sealed interface AgentEvent
        permits AgentEvent.AssistantTextDelta, AgentEvent.ToolStarted,
                AgentEvent.ToolFinished, AgentEvent.TurnFinished {

    /** 모델이 흘려보낸 텍스트 조각. */
    record AssistantTextDelta(String text) implements AgentEvent {}
    /** 도구 실행 시작(이름 + 모델이 만든 입력). */
    record ToolStarted(String name, JsonNode input) implements AgentEvent {}
    /** 도구 실행 종료(오류 여부). */
    record ToolFinished(String name, boolean isError) implements AgentEvent {}
    /** 루프 종료(종료 사유). */
    record TurnFinished(Transition transition) implements AgentEvent {}
}
