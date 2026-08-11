package agent.llm.gemini;

import agent.llm.ToolSpec;
import agent.message.*;
import agent.message.ContentBlock.*;
import com.fasterxml.jackson.databind.node.*;

import java.util.*;

final class GeminiWire {
    private GeminiWire() {}

    /**
     * 우리 Message[] → Gemini contents[]. role은 user/model 둘뿐.
     * tool_result(functionResponse)는 user 역할 part로 둔다(Anthropic의 user content 블록과 같은 자리).
     */
    static ArrayNode contents(List<Message> messages) {
        ArrayNode arr = Json.MAPPER.createArrayNode();
        Map<String, String> toolNames = new HashMap<>();   // toolUseId → 함수 이름(functionResponse 매칭용)
        for (Message m : messages) {
            switch (m) {
                case Message.NoticeMessage ignored -> { /* 내부 알림은 전송 안 함 */ }
                case Message.AssistantMessage a -> {
                    toolNames.clear();                     // 합성 id(call_N)는 턴마다 재사용된다 — 직전 assistant 것만 유효
                    for (ContentBlock b : a.content()) {
                        if (b instanceof ToolUseBlock u) toolNames.put(u.id(), u.name());
                    }
                    arr.addObject().put("role", "model").set("parts", parts(a.content(), toolNames));
                }
                case Message.UserMessage u ->
                        arr.addObject().put("role", "user").set("parts", parts(u.content(), toolNames));
            }
        }
        return arr;
    }

    /** 우리 ContentBlock[] → Gemini parts[]. */
    private static ArrayNode parts(List<ContentBlock> blocks, Map<String, String> toolNames) {
        ArrayNode arr = Json.MAPPER.createArrayNode();
        for (ContentBlock b : blocks) {
            switch (b) {
                case TextBlock t -> arr.addObject().put("text", t.text());
                case ToolUseBlock u -> {
                    ObjectNode fc = arr.addObject().putObject("functionCall");
                    fc.put("name", u.name());
                    fc.set("args", u.input());
                }
                case ToolResultBlock r -> {
                    ObjectNode fr = arr.addObject().putObject("functionResponse");
                    // ⚠ Gemini는 functionResponse를 함수 *이름*으로 매칭한다 — 직전 assistant의
                    //   tool_use에서 id→이름을 역참조한다. (우리 toolUseId는 GeminiClient가 합성한
                    //   call_N이라 그대로 보내면 매칭이 깨진다.)
                    fr.put("name", toolNames.getOrDefault(r.toolUseId(), r.toolUseId()));
                    fr.putObject("response").put("content", flattenText(r.content()));
                }
                case ThinkingBlock th -> arr.addObject().put("text", th.thinking());
                case ImageBlock img -> arr.addObject().put("text", "");  // inlineData 형식은 본 장 범위 밖
            }
        }
        return arr;
    }

    /** system 문자열 → Gemini systemInstruction. */
    static ObjectNode systemInstruction(String system) {
        ObjectNode si = Json.MAPPER.createObjectNode();
        si.putArray("parts").addObject().put("text", system);
        return si;
    }

    /** 우리 ToolSpec[] → Gemini tools[{functionDeclarations:[...]}]. */
    static ArrayNode tools(List<ToolSpec> specs) {
        ArrayNode arr = Json.MAPPER.createArrayNode();
        ArrayNode decls = arr.addObject().putArray("functionDeclarations");
        for (ToolSpec t : specs) {
            ObjectNode d = decls.addObject();
            d.put("name", t.name()).put("description", t.description());
            d.set("parameters", t.inputSchema());
        }
        return arr;
    }

    private static String flattenText(List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof TextBlock t) sb.append(t.text());
        }
        return sb.toString();
    }
}
