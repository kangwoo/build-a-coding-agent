package agent.tool.schema;

import agent.message.Json;
import agent.tool.builtin.EchoTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaTest {

    @Test
    void generates_schema_from_record() {
        JsonNode schema = new EchoTool().inputSchema();
        assertThat(schema.path("properties").path("text").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").path("text").path("description").asText()).isEqualTo("돌려줄 텍스트");
        assertThat(schema.path("required").get(0).asText()).isEqualTo("text");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void rejects_missing_required_field() {
        ObjectNode in = Json.MAPPER.createObjectNode();   // text 없음
        assertThat(SchemaValidator.validate(in, new EchoTool().inputSchema()))
                .get().asString().contains("필수 필드 누락: text");
    }

    @Test
    void rejects_unknown_field() {
        ObjectNode in = Json.MAPPER.createObjectNode();
        in.put("text", "hi"); in.put("sneaky", true);
        assertThat(SchemaValidator.validate(in, new EchoTool().inputSchema()))
                .get().asString().contains("알 수 없는 필드: sneaky");
    }

    @Test
    void accepts_valid_input() {
        ObjectNode in = Json.MAPPER.createObjectNode().put("text", "hi");
        assertThat(SchemaValidator.validate(in, new EchoTool().inputSchema())).isEmpty();
    }

    @Test
    void rejects_type_mismatch() {
        // string 자리에 숫자 — Jackson은 "42"로 강제변환하지만 스키마 검증이 먼저 막는다.
        ObjectNode in = Json.MAPPER.createObjectNode().put("text", 42);
        assertThat(SchemaValidator.validate(in, new EchoTool().inputSchema()))
                .get().asString().contains("타입 불일치");
    }

    /** boolean(없으면 false)·Optional은 선택이고, 그 밖의 필드만 required다. */
    record Flags(String name, boolean verbose, java.util.Optional<Integer> count) {}

    @Test
    void boolean_and_optional_are_not_required() {
        JsonNode schema = JsonSchemas.forRecord(Flags.class);

        var required = new java.util.ArrayList<String>();
        schema.path("required").forEach(n -> required.add(n.asText()));
        assertThat(required).containsExactly("name");                       // verbose·count 제외

        assertThat(schema.path("properties").path("count").path("type").asText())
                .isEqualTo("integer");                                       // Optional<Integer> → integer
    }
}
