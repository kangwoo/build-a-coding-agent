package agent.llm;

import agent.exec.CancellationToken;
import agent.message.Message.AssistantMessage;

public interface LlmClient {

    /** 비스트리밍 호출. 응답을 통째로 받아 최종 메시지로 돌려준다. */
    AssistantMessage create(LlmRequest request, CancellationToken cancel);

    /** 이 provider가 지원하는 기능 집합. */
    LlmCapabilities capabilities();

    /** 식별용 이름(로그·--provider 매칭). */
    String name();
}
