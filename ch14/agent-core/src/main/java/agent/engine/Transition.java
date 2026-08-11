package agent.engine;

/** 에이전트 루프가 끝나는 이유. sealed로 못 박아 처리 누락을 컴파일러가 잡게 한다. */
public sealed interface Transition
        permits Transition.Completed, Transition.MaxTurns,
                Transition.ModelError, Transition.Aborted {
    /** 정상 완료 — 더 이상 tool_use가 없다. */
    record Completed() implements Transition {}
    /** 최대 턴 초과 — 무한 루프 방지. */
    record MaxTurns(int turns) implements Transition {}
    /** 모델/API 오류. */
    record ModelError(String message) implements Transition {}
    /** 사용자 취소(14장에서 Ctrl+C와 연결). */
    record Aborted(String reason) implements Transition {}
}
