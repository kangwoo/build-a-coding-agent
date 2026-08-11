package agent.tool;

import agent.message.ContentBlock.ToolResultBlock;
import agent.message.ContentBlock.ToolUseBlock;
import agent.message.Json;

public final class ToolExecutor {
    private ToolExecutor() {}

    /**
     * tool_use 한 개를 실행해 tool_result 한 개로. 어떤 실패도 예외를 흘리지 않고 결과로 표현한다.
     * (① 도구 찾기는 호출자(12·13장 엔진/오케스트레이터)의 몫이다. 여기는 ②부터 시작한다.)
     */
    public static ToolResultBlock runToolUse(Tool<?, ?> tool, ToolUseBlock use, ToolContext ctx) {
        return exec(tool, use, ctx);   // 와일드카드 캡처용 위임
    }

    private static <I, O> ToolResultBlock exec(Tool<I, O> tool, ToolUseBlock use, ToolContext ctx) {
        // ② 입력 역직렬화 (구조: 깨진 JSON·미지 필드 거부. 엄격한 타입·필수 검증은 8장)
        I input;
        try {
            input = Json.MAPPER.treeToValue(use.input(), tool.inputType());
        } catch (Exception e) {
            return error(use.id(), "InputValidationError: " + e.getMessage());
        }
        if (input == null) {   // JSON null/빈 입력은 역직렬화를 통과하지만 도구가 다룰 수 없다
            return error(use.id(), "InputValidationError: 입력이 비어 있습니다");
        }

        // ③ 의미 검증 → ④ 권한 → ⑤ 실행 → ⑥ 매핑. 어떤 예외도 결과로 가둔다(짝 없는 tool_use 금지).
        try {
            // ③ 의미 검증
            if (tool.validateInput(input, ctx) instanceof ValidationResult.Fail f) {
                return error(use.id(), f.message());      // errorCode는 10장에서 모델에 구조화 전달
            }

            // ④ 권한: Allow가 아니면(Deny·Ask) 보수적으로 막는다(fail-closed). 대화형 ask는 16장.
            PermissionResult perm = tool.checkPermissions(input, ctx);
            if (!(perm instanceof PermissionResult.Allow)) {
                String why = perm instanceof PermissionResult.Deny d ? d.message()
                        : "사용자 승인이 필요합니다(16장)";       // Ask
                return error(use.id(), why);
            }

            // ⑤ 실행 → ⑥ 결과 매핑
            ToolResult<O> result = tool.call(input, ctx);
            return tool.mapResult(result.data(), use.id());
        } catch (Exception e) {
            return error(use.id(), "도구 실행 오류: " + e.getMessage());
        }
    }

    /** 모든 실패를 모델이 인식하는 &lt;tool_use_error&gt; 봉투로 통일한다. */
    private static ToolResultBlock error(String toolUseId, String message) {
        return ToolResultBlock.error(toolUseId, "<tool_use_error>" + message + "</tool_use_error>");
    }
}
