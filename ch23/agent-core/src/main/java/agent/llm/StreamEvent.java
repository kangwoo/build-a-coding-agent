// agent-core/src/main/java/agent/llm/StreamEvent.java
package agent.llm;

import agent.message.ContentBlock;
import agent.message.Usage;

public sealed interface StreamEvent
        permits StreamEvent.MessageStart, StreamEvent.BlockStart, StreamEvent.BlockDelta,
                StreamEvent.BlockStop, StreamEvent.MessageDelta, StreamEvent.MessageStop,
                StreamEvent.ApiError {

    record MessageStart(Usage usage) implements StreamEvent {}
    record BlockStart(int index, ContentBlock initial) implements StreamEvent {}
    record BlockDelta(int index, Delta delta) implements StreamEvent {}
    record BlockStop(int index) implements StreamEvent {}
    record MessageDelta(Usage usage, String stopReason) implements StreamEvent {}
    record MessageStop() implements StreamEvent {}
    record ApiError(String message, int httpStatus) implements StreamEvent {}

    /** 블록 내부로 흘러드는 조각들. */
    sealed interface Delta permits TextDelta, ThinkingDelta, SignatureDelta, InputJsonDelta {}
    record TextDelta(String text) implements Delta {}
    record ThinkingDelta(String thinking) implements Delta {}      // 추론 모델의 사고 조각(24장)
    record SignatureDelta(String signature) implements Delta {}    // 사고 블록 서명 조각 — 시작이 아니라 흐름 중간에 온다(24장)
    record InputJsonDelta(String partialJson) implements Delta {}  // tool_use 인자 누적(12장)
}
