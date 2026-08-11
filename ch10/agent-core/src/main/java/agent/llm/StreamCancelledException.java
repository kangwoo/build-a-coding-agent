// agent-core/src/main/java/agent/llm/StreamCancelledException.java
package agent.llm;

import java.util.concurrent.CancellationException;

/**
 * 소비자 스레드의 인터럽트로 스트림 소비가 끊겼음을 알리는 전용 타입.
 * 표준 CancellationException은 IllegalStateException의 하위 타입이라, 그대로 던지면
 * 호출부의 catch(IllegalStateException)이 'API 오용'(submit() 재진입 등, 12장)을
 * 잡으려다 '취소'까지 삼킨다. 전용 하위 타입은 취소를 먼저 골라 잡을 수 있게 한다.
 * 스트림만이 아니다 — create() 같은 블로킹 LLM 호출의 인터럽트도 이 타입으로 승격한다.
 */
public final class StreamCancelledException extends CancellationException {
    public StreamCancelledException(String message) { super(message); }
}
