package agent.tool.schema;

import agent.message.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class JsonSchemas {
    private JsonSchemas() {}

    private static final Map<Class<?>, JsonNode> CACHE = new ConcurrentHashMap<>();

    /** record 타입으로부터 JSON Schema 생성(메모이즈). 반환은 복사본 — 호출자 변형이 캐시를 오염시키지 않는다. */
    public static JsonNode forRecord(Class<?> recordType) {
        return CACHE.computeIfAbsent(recordType, JsonSchemas::build).deepCopy();
    }

    private static JsonNode build(Class<?> type) {
        if (!type.isRecord()) {                                           // record만 지원(아니면 NPE 대신 명확한 오류)
            throw new IllegalArgumentException("스키마를 만들 수 없습니다(record가 아님): " + type.getName());
        }
        ObjectNode schema = Json.MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ArrayNode required = Json.MAPPER.createArrayNode();

        for (RecordComponent rc : type.getRecordComponents()) {
            ObjectNode p = props.putObject(rc.getName());
            fillType(p, rc.getType(), rc.getGenericType());

            Desc desc = rc.getAnnotation(Desc.class);
            if (desc != null) p.put("description", desc.value());

            if (isRequired(rc.getType())) required.add(rc.getName());
        }
        if (!required.isEmpty()) schema.set("required", required);
        schema.put("additionalProperties", false);                       // strict: 모르는 필드 거부
        return schema;
    }

    private static void fillType(ObjectNode node, Class<?> raw, Type generic) {
        if (raw == String.class || raw == Path.class || raw == char.class) {
            node.put("type", "string");
        } else if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class) {
            node.put("type", "integer");
        } else if (raw == double.class || raw == Double.class || raw == float.class || raw == Float.class) {
            node.put("type", "number");
        } else if (raw == boolean.class || raw == Boolean.class) {
            node.put("type", "boolean");
        } else if (raw.isEnum()) {
            node.put("type", "string");
            ArrayNode en = node.putArray("enum");
            for (Object c : raw.getEnumConstants()) en.add(((Enum<?>) c).name());
        } else if (raw == Optional.class) {
            fillType(node, typeArg(generic), null);                       // Optional<T> → T (한 겹만 펼침)
        } else if (List.class.isAssignableFrom(raw)) {
            node.put("type", "array");
            fillType(node.putObject("items"), typeArg(generic), null);
        } else {
            node.put("type", "object");                                   // 중첩 객체(간단 처리)
        }
    }

    /** Optional은 선택, primitive boolean은 '없으면 false'라 선택. 그 밖의 필드는 필수. */
    private static boolean isRequired(Class<?> raw) {
        return raw != Optional.class && raw != boolean.class;
    }

    private static Class<?> typeArg(Type generic) {
        if (generic instanceof ParameterizedType pt
                && pt.getActualTypeArguments()[0] instanceof Class<?> c) return c;
        return String.class;                                             // 알 수 없으면 string
    }
}
