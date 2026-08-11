package agent.tool;

public sealed interface ValidationResult permits ValidationResult.Ok, ValidationResult.Fail {

    static ValidationResult ok() { return Ok.INSTANCE; }
    static ValidationResult fail(String message, int errorCode) { return new Fail(message, errorCode); }

    record Ok() implements ValidationResult { static final Ok INSTANCE = new Ok(); }

    /** errorCode는 각 도구가 자유롭게 정하는 의미 검증 실패 코드(전역 의미 없음). 9·10장 파일 도구에서 쓰인다. */
    record Fail(String message, int errorCode) implements ValidationResult {}
}
