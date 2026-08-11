package agent.tool;

/** 도구 실행의 구조화된 결과. 지금은 data만. 이후 장에서 필드가 늘어난다. */
public record ToolResult<O>(O data) {
    public static <O> ToolResult<O> of(O data) { return new ToolResult<>(data); }
}
