// agent-core/src/main/java/agent/llm/EventStream.java
package agent.llm;

/** 도착하는 대로 순회하는 이벤트 흐름. try-with-resources로 닫는다. */
public interface EventStream<T> extends Iterable<T>, AutoCloseable {
    @Override void close();   // checked 예외 없이 닫힌다
}
