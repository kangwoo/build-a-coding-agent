package agent.llm;

import agent.exec.CancellationToken;
import agent.message.Message.AssistantMessage;

public interface LlmClient {

    /** 스트리밍 호출. 도착하는 순서대로 순회 가능한 이벤트 흐름. (6장 추가) */
    EventStream<StreamEvent> stream(LlmRequest request, CancellationToken cancel);

    /** 비스트리밍 호출(폴백·요약 등에서 사용. 5장 그대로). */
    AssistantMessage create(LlmRequest request, CancellationToken cancel);

    /** 이 provider가 지원하는 기능 집합. */
    LlmCapabilities capabilities();

    /** 식별용 이름(로그·--provider 매칭). */
    String name();
}
