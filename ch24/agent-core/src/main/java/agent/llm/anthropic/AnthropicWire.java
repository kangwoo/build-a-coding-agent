package agent.llm.anthropic;

import agent.llm.LlmCapabilities;
import agent.llm.SystemBlock;
import agent.message.*;
import agent.message.ContentBlock.*;
import com.fasterxml.jackson.databind.node.*;

import java.util.List;

final class AnthropicWire {
    private AnthropicWire() {}

    /** 우리 Message[] → Anthropic messages[]. system은 본문에 넣지 않고 별도 필드(toWireBody)에서 처리. */
    static ArrayNode messages(List<Message> messages, LlmCapabilities caps) {
        ArrayNode arr = Json.MAPPER.createArrayNode();
        for (Message m : messages) {
            switch (m) {
                case Message.NoticeMessage ignored -> { /* 내부 알림은 전송 안 함 */ }
                case Message.AssistantMessage a ->
                        arr.addObject().put("role", "assistant").set("content", blocks(a.content(), caps, false));
                case Message.UserMessage u ->
                        arr.addObject().put("role", "user").set("content", blocks(u.content(), caps, true));
            }
        }
        return arr;
    }

    /** 우리 ContentBlock[] → Anthropic content 배열. 같은 role 안에서 tool_use/tool_result가 공존한다. */
    private static ArrayNode blocks(List<ContentBlock> blocks, LlmCapabilities caps, boolean userSide) {
        ArrayNode arr = Json.MAPPER.createArrayNode();
        for (ContentBlock b : blocks) {
            switch (b) {
                case TextBlock t -> arr.addObject().put("type", "text").put("text", t.text());
                case ToolUseBlock u -> {
                    ObjectNode w = arr.addObject();
                    w.put("type", "tool_use").put("id", u.id()).put("name", u.name());
                    w.set("input", u.input());
                }
                case ToolResultBlock r -> {
                    ObjectNode w = arr.addObject();
                    w.put("type", "tool_result").put("tool_use_id", r.toolUseId());
                    w.put("is_error", r.isError());
                    w.set("content", blocks(r.content(), caps, userSide));
                }
                case ThinkingBlock th -> {
                    ObjectNode w = arr.addObject();
                    w.put("type", "thinking").put("thinking", th.thinking());
                    if (th.signature() != null) w.put("signature", th.signature());
                }
                case ImageBlock img -> arr.addObject().put("type", "image");  // 실전송 시 source.{type,media_type,data} 필요(9장 source 형식, 본 장 범위 밖)
            }
        }
        return arr;
    }

    /** 우리 SystemBlock[] → Anthropic system 블록 배열. 캐싱 지원 시 마지막 정적 블록에 cache_control 1개. */
    static ArrayNode systemBlocks(List<SystemBlock> system, boolean caching) {
        int lastStatic = -1;                       // 동적뿐이면 -1 — 안정 프리픽스가 없어 마커를 생략한다
        for (int i = 0; i < system.size(); i++) {
            if (!system.get(i).dynamic()) lastStatic = i;
        }
        ArrayNode arr = Json.MAPPER.createArrayNode();
        for (int i = 0; i < system.size(); i++) {
            ObjectNode block = arr.addObject().put("type", "text").put("text", system.get(i).text());
            if (caching && i == lastStatic) {
                // 캐시 마커는 요청당 정확히 1개 — 정적/동적 경계(마지막 정적 블록)에 찍는다 (함정 참조)
                block.putObject("cache_control").put("type", "ephemeral");
            }
        }
        return arr;
    }
}
