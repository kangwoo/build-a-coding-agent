package agent.mcp;

import agent.message.ContentBlock;
import agent.message.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class McpToolMappingTest {

    @Test
    void name_is_normalized() {
        JsonNode desc = Json.read("{\"name\":\"read-file\",\"description\":\"d\"}", JsonNode.class);
        var tool = new McpTool(null, "fs.server", desc);
        assertThat(tool.name()).isEqualTo("mcp__fs_server__read-file");   // 점→_, 규칙 정규화
    }

    @Test
    void result_content_maps_to_blocks() {
        JsonNode desc = Json.read("{\"name\":\"x\"}", JsonNode.class);
        var tool = new McpTool(null, "s", desc);
        JsonNode result = Json.read(
                "{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"isError\":false}", JsonNode.class);

        var block = tool.mapResult(result, "tu");
        assertThat(block.isError()).isFalse();
        assertThat(((ContentBlock.TextBlock) block.content().get(0)).text()).isEqualTo("hello");
    }

    @Test
    void is_error_flag_is_propagated() {
        var tool = new McpTool(null, "s", Json.read("{\"name\":\"x\"}", JsonNode.class));
        JsonNode result = Json.read(
                "{\"content\":[{\"type\":\"text\",\"text\":\"boom\"}],\"isError\":true}", JsonNode.class);

        assertThat(tool.mapResult(result, "tu").isError()).isTrue();
    }

    @Test
    void empty_content_yields_placeholder_block() {
        var tool = new McpTool(null, "s", Json.read("{\"name\":\"x\"}", JsonNode.class));
        JsonNode result = Json.read("{\"content\":[],\"isError\":false}", JsonNode.class);

        var block = tool.mapResult(result, "tu");
        assertThat(block.content()).hasSize(1);
        assertThat(((ContentBlock.TextBlock) block.content().get(0)).text()).isEqualTo("(빈 결과)");
    }

    @Test
    void image_content_maps_to_image_block() {
        var tool = new McpTool(null, "s", Json.read("{\"name\":\"x\"}", JsonNode.class));
        JsonNode result = Json.read(
                "{\"content\":[{\"type\":\"image\",\"mimeType\":\"image/jpeg\",\"data\":\"AAAA\"}]}", JsonNode.class);

        var block = tool.mapResult(result, "tu");
        assertThat(block.content().get(0)).isInstanceOf(ContentBlock.ImageBlock.class);
        var img = (ContentBlock.ImageBlock) block.content().get(0);
        assertThat(img.mediaType()).isEqualTo("image/jpeg");
        assertThat(img.dataBase64()).isEqualTo("AAAA");
    }

    @Test
    void unknown_type_falls_back_to_text() {
        var tool = new McpTool(null, "s", Json.read("{\"name\":\"x\"}", JsonNode.class));
        JsonNode result = Json.read(
                "{\"content\":[{\"type\":\"resource\",\"uri\":\"file:///x\"}]}", JsonNode.class);

        var block = tool.mapResult(result, "tu");
        assertThat(block.content().get(0)).isInstanceOf(ContentBlock.TextBlock.class);
        assertThat(((ContentBlock.TextBlock) block.content().get(0)).text()).contains("resource");
    }

    /**
     * 회귀 테스트: inputSchema가 없는 디스크립터의 inputSchema()는 예외를 던지면 안 되고
     * 허용형 빈 객체 스키마({"type":"object"})를 돌려줘야 한다.
     * (수정 전엔 Tool.super.inputSchema()가 JsonSchemas.forRecord(JsonNode.class)를 호출해
     *  IllegalArgumentException을 던졌다.)
     */
    @Test
    void input_schema_falls_back_to_permissive_object_when_missing() {
        var tool = new McpTool(null, "s", Json.read("{\"name\":\"x\"}", JsonNode.class));

        assertThatCode(tool::inputSchema).doesNotThrowAnyException();
        JsonNode schema = tool.inputSchema();
        assertThat(schema.path("type").asText()).isEqualTo("object");
    }

    @Test
    void input_schema_passes_through_when_present() {
        JsonNode desc = Json.read(
                "{\"name\":\"x\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}}",
                JsonNode.class);
        var tool = new McpTool(null, "s", desc);

        JsonNode schema = tool.inputSchema();
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("required").get(0).asText()).isEqualTo("path");
    }
}
