// agent-core/src/main/java/agent/mcp/McpTool.java
package agent.mcp;

import agent.message.ContentBlock;
import agent.message.ContentBlock.ToolResultBlock;
import agent.message.Json;
import agent.tool.*;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/** 외부 MCP 도구 1개를 우리 Tool로 감싼다. 입력은 임의 JSON이므로 JsonNode. */
public final class McpTool implements Tool<JsonNode, JsonNode> {

    private final StdioMcpClient client;
    private final String serverName;
    private final String originalName;   // 서버 쪽 원래 이름
    private final String description;
    private final JsonNode schema;

    public McpTool(StdioMcpClient client, String serverName, JsonNode descriptor) {
        this.client = client;
        this.serverName = serverName;
        this.originalName = descriptor.path("name").asText();
        this.description = descriptor.path("description").asText("");
        this.schema = descriptor.path("inputSchema");
    }

    @Override public String name() { return "mcp__" + norm(serverName) + "__" + norm(originalName); }
    @Override public String description() { return description; }
    @Override public Class<JsonNode> inputType() { return JsonNode.class; }   // 그대로 통과

    /**
     * MCP 도구는 inputSchema를 생략할 수 있다. 그럴 땐 Tool 기본값을 쓰면 안 된다
     * (기본값은 JsonSchemas.forRecord(JsonNode.class)를 호출하는데 JsonNode는 record가
     * 아니라 IllegalArgumentException이 난다). 비어 있으면 허용형 빈 객체 스키마로 대체한다.
     */
    @Override public JsonNode inputSchema() {
        return (schema == null || schema.isMissingNode())
                ? Json.MAPPER.createObjectNode().put("type", "object")
                : schema;
    }
    // 안전성은 알 수 없으니 보수적으로 기본값(false) 유지

    @Override
    public ToolResult<JsonNode> call(JsonNode input, ToolContext ctx) throws Exception {
        return ToolResult.of(client.callTool(originalName, input));
    }

    @Override
    public ToolResultBlock mapResult(JsonNode result, String toolUseId) {
        // MCP 결과의 content[] → 우리 ContentBlock으로 변환
        List<ContentBlock> blocks = new ArrayList<>();
        boolean isError = result.path("isError").asBoolean(false);
        for (JsonNode c : result.path("content")) {
            switch (c.path("type").asText()) {
                case "text" -> blocks.add(new ContentBlock.TextBlock(c.path("text").asText()));
                // 큰 blob도 base64로 그대로 인라인한다. 디스크 저장 후 경로 치환은 생략(25.5 함정).
                case "image" -> blocks.add(new ContentBlock.ImageBlock(
                        c.path("mimeType").asText("image/png"), c.path("data").asText()));
                default -> blocks.add(new ContentBlock.TextBlock(c.toString()));
            }
        }
        if (blocks.isEmpty()) blocks.add(new ContentBlock.TextBlock("(빈 결과)"));
        return new ToolResultBlock(toolUseId, blocks, isError);
    }

    /** 이름 정규화: 영숫자/_/- 만, 그 외는 _. "a.b"와 "a_b"가 같은 이름으로 붕괴할 수 있다(충돌 시 레지스트리가 덮어씀). */
    private static String norm(String s) { return s.replaceAll("[^a-zA-Z0-9_-]", "_"); }
}
