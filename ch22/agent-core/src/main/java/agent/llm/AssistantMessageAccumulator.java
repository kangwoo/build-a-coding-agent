// agent-core/src/main/java/agent/llm/AssistantMessageAccumulator.java
package agent.llm;

import agent.message.*;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** StreamEvent들을 모아 하나의 AssistantMessage로 조립한다. (provider 중립) */
public final class AssistantMessageAccumulator {

    // 블록 인덱스 → 누적 중인 본문/서명/도구입력 (본문은 텍스트·사고 공용 — 한 블록엔 한 종류만 흐른다)
    private final TreeMap<Integer, ContentBlock> starts = new TreeMap<>();
    private final TreeMap<Integer, StringBuilder> bodyBuf = new TreeMap<>();
    private final TreeMap<Integer, StringBuilder> sigBuf = new TreeMap<>();
    private final TreeMap<Integer, StringBuilder> jsonBuf = new TreeMap<>();
    private Usage usage = Usage.EMPTY;
    private String stopReason;

    public void accept(StreamEvent e) {
        switch (e) {
            case StreamEvent.MessageStart ms -> usage = usage.mergeCumulative(ms.usage());
            case StreamEvent.BlockStart bs -> starts.put(bs.index(), bs.initial());
            case StreamEvent.BlockDelta bd -> appendDelta(bd.index(), bd.delta());
            case StreamEvent.BlockStop bs -> { /* 완성은 build()에서 */ }
            case StreamEvent.MessageDelta md -> {
                usage = usage.mergeCumulative(md.usage());
                if (md.stopReason() != null) stopReason = md.stopReason();
            }
            case StreamEvent.MessageStop ms -> { }
            case StreamEvent.ApiError err ->
                throw new RuntimeException("API 오류: " + err.message());
        }
    }

    private void appendDelta(int index, StreamEvent.Delta d) {
        switch (d) {
            case StreamEvent.TextDelta t ->
                bodyBuf.computeIfAbsent(index, k -> new StringBuilder()).append(t.text());
            case StreamEvent.ThinkingDelta t ->
                bodyBuf.computeIfAbsent(index, k -> new StringBuilder()).append(t.thinking());
            case StreamEvent.SignatureDelta s ->
                sigBuf.computeIfAbsent(index, k -> new StringBuilder()).append(s.signature());
            case StreamEvent.InputJsonDelta j ->
                jsonBuf.computeIfAbsent(index, k -> new StringBuilder()).append(j.partialJson());
        }
    }

    public Message.AssistantMessage build() {
        List<ContentBlock> blocks = new ArrayList<>();
        for (var entry : starts.entrySet()) {
            int i = entry.getKey();
            ContentBlock initial = entry.getValue();
            switch (initial) {
                case ContentBlock.TextBlock t ->
                    blocks.add(new ContentBlock.TextBlock(body(i)));
                case ContentBlock.ThinkingBlock th ->
                    blocks.add(new ContentBlock.ThinkingBlock(body(i), signature(i, th.signature())));
                case ContentBlock.ToolUseBlock u -> {
                    JsonNode input = parseJson(i);            // 마지막에 한 번만 파싱(함정 6.6)
                    blocks.add(new ContentBlock.ToolUseBlock(u.id(), u.name(), input));
                }
                default -> { }
            }
        }
        return Message.AssistantMessage.of(blocks, usage, stopReason);
    }

    private String body(int i) {
        StringBuilder sb = bodyBuf.get(i);
        return sb == null ? "" : sb.toString();
    }

    /** 서명은 흐름 중간에 SignatureDelta로 도착한다. 델타가 없으면 시작 블록의 서명으로 폴백. */
    private String signature(int i, String initial) {
        StringBuilder sb = sigBuf.get(i);
        return sb == null || sb.isEmpty() ? initial : sb.toString();
    }

    private JsonNode parseJson(int i) {
        StringBuilder sb = jsonBuf.get(i);
        if (sb == null || sb.isEmpty()) return Json.MAPPER.createObjectNode();
        return Json.read(sb.toString(), JsonNode.class);
    }
}
