package agent.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ContentBlock.TextBlock.class,       name = "text"),
    @JsonSubTypes.Type(value = ContentBlock.ThinkingBlock.class,   name = "thinking"),
    @JsonSubTypes.Type(value = ContentBlock.ToolUseBlock.class,    name = "tool_use"),
    @JsonSubTypes.Type(value = ContentBlock.ToolResultBlock.class, name = "tool_result"),
    @JsonSubTypes.Type(value = ContentBlock.ImageBlock.class,      name = "image"),
})
public sealed interface ContentBlock
        permits ContentBlock.TextBlock, ContentBlock.ThinkingBlock,
                ContentBlock.ToolUseBlock, ContentBlock.ToolResultBlock,
                ContentBlock.ImageBlock {

    record TextBlock(String text) implements ContentBlock {}

    record ThinkingBlock(String thinking, String signature) implements ContentBlock {}

    /** assistant가 생성하는 도구 호출. input은 모델이 만든 임의 JSON. */
    record ToolUseBlock(String id, String name, JsonNode input) implements ContentBlock {}

    /** 도구 실행 결과. user 역할 메시지로 모델에게 되돌려 보낸다. */
    record ToolResultBlock(String toolUseId, List<ContentBlock> content, boolean isError)
            implements ContentBlock {
        public static ToolResultBlock ok(String toolUseId, String text) {
            return new ToolResultBlock(toolUseId, List.of(new TextBlock(text)), false);
        }
        public static ToolResultBlock error(String toolUseId, String text) {
            return new ToolResultBlock(toolUseId, List.of(new TextBlock(text)), true);
        }
    }

    record ImageBlock(String mediaType, String dataBase64) implements ContentBlock {}
}
