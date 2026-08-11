package agent.tool;

import agent.message.ContentBlock.ToolResultBlock;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * 모든 도구의 공통 계약. I=입력 record, O=출력 타입.
 * 필수: name, description, inputType, call, mapResult.
 * 나머지는 안전한 기본값(default)을 가진다.
 */
public interface Tool<I, O> {

    // ── 필수 ──────────────────────────────────────────────
    String name();

    /** 모델에게 보여줄 도구 설명(언제 어떻게 쓰는지). */
    String description();

    /** 모델이 보낸 JSON input을 이 타입으로 역직렬화한다(8장에서 스키마와 연결). */
    Class<I> inputType();

    /** 실제 실행. */
    ToolResult<O> call(I input, ToolContext ctx) throws Exception;

    /** 출력 O를 모델용 tool_result 블록으로 변환. (화면 렌더링이 아니라 '모델이 읽을' 형태) */
    ToolResultBlock mapResult(O output, String toolUseId);

    // ── 안전한 기본값(default) ─────────────────────────────
    default Set<String> aliases() { return Set.of(); }

    /** 부수효과 없이 읽기만 하나? 기본 false(fail-closed). */
    default boolean isReadOnly(I input) { return false; }

    /** 다른 도구와 동시에 실행해도 안전한가? 기본 false(fail-closed). 13장 오케스트레이션에서 사용. */
    default boolean isConcurrencySafe(I input) { return false; }

    /** 파일 삭제 등 파괴적인가? 기본 false. */
    default boolean isDestructive(I input) { return false; }

    /** 의미 검증(파일 존재 등). 기본은 통과. */
    default ValidationResult validateInput(I input, ToolContext ctx) { return ValidationResult.ok(); }

    /** 권한 확인. 기본은 허용(실제 규칙은 16장). */
    default PermissionResult checkPermissions(I input, ToolContext ctx) { return PermissionResult.allow(); }

    /** 입력 JSON Schema. 8장에서 record로부터 생성한다. 기본은 빈 객체. */
    default JsonNode inputSchema() { return agent.message.Json.MAPPER.createObjectNode(); }
}
