package agent.tool;

import agent.message.ContentBlock.ToolResultBlock;
import agent.message.ContentBlock.ToolUseBlock;
import agent.message.Json;

import java.util.concurrent.CancellationException;

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
        // ②-a 구조 검증 (스키마: 객체·필수 필드·모르는 필드 거부)
        var schemaError = agent.tool.schema.SchemaValidator.validate(use.input(), tool.inputSchema());
        if (schemaError.isPresent()) {
            return error(use.id(), "InputValidationError: " + schemaError.get());
        }

        // ②-b 역직렬화 (구조·타입은 ②-a가 통과시킨 뒤 — 여기선 record로 매핑만)
        I input;
        try {
            input = Json.MAPPER.treeToValue(use.input(), tool.inputType());
        } catch (Exception e) {
            return error(use.id(), "InputValidationError: " + e.getMessage());
        }
        if (input == null) {   // ②-a가 대개 먼저 잡지만, 방어적으로 한 번 더(빈/null 입력)
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
        } catch (CancellationException e) {
            // 취소는 오류가 아니다 — 취소 단락(short-circuit)의 합성 결과와 같은 '취소됨' 표기로 돌려준다.
            return error(use.id(), "취소됨: " + use.name());
        } catch (Exception e) {
            return error(use.id(), "도구 실행 오류: " + e.getMessage());
        }
    }

    /** 모든 실패를 모델이 인식하는 &lt;tool_use_error&gt; 봉투로 통일한다. */
    private static ToolResultBlock error(String toolUseId, String message) {
        return ToolResultBlock.error(toolUseId, "<tool_use_error>" + message + "</tool_use_error>");
    }
}
