package agent.tool.schema;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/** 우리가 생성한 스키마로 입력 JSON을 검사한다(범용 JSON Schema 검증기가 아니다). */
public final class SchemaValidator {
    private SchemaValidator() {}

    /** 통과면 Optional.empty(), 실패면 사람이 읽을 오류 메시지. */
    public static Optional<String> validate(JsonNode input, JsonNode schema) {
        if (input == null || !input.isObject()) {
            return Optional.of("입력은 JSON 객체여야 합니다.");
        }
        JsonNode props = schema.path("properties");
        // additionalProperties 생략 시 허용이 JSON Schema 기본값. 우리 스키마는 항상 false(strict).
        boolean strict = !schema.path("additionalProperties").asBoolean(true);

        // 필수 필드 존재 — JSON null은 '없음'으로 취급한다.
        for (JsonNode r : schema.path("required")) {
            String name = r.asText();
            if (!input.has(name) || input.get(name).isNull()) {
                return Optional.of("필수 필드 누락: " + name);
            }
        }
        // 각 입력 필드: 미지 필드 거부(strict) + 타입 일치(스칼라 강제변환 차단)
        for (Iterator<String> it = input.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            if (!props.has(f)) {
                if (strict) return Optional.of("알 수 없는 필드: " + f);
                continue;
            }
            JsonNode value = input.get(f);
            String type = props.path(f).path("type").asText("");
            if (!value.isNull() && !type.isEmpty() && !typeMatches(type, value)) {
                return Optional.of("타입 불일치: '" + f + "'는 " + type + " 여야 합니다.");
            }
        }
        return Optional.empty();
    }

    /** 선언한 JSON Schema 타입과 실제 JSON 노드 종류를 대조한다. */
    private static boolean typeMatches(String type, JsonNode v) {
        return switch (type) {
            case "string"  -> v.isTextual();
            case "integer" -> v.isIntegralNumber();
            case "number"  -> v.isNumber();
            case "boolean" -> v.isBoolean();
            case "array"   -> v.isArray();
            case "object"  -> v.isObject();
            default        -> true;
        };
    }
}
