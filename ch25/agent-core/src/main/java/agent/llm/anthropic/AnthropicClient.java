package agent.llm.anthropic;

import agent.exec.CancellationToken;
import agent.llm.*;
import agent.message.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.stream.*;

public final class AnthropicClient implements LlmClient {

    private final AnthropicConfig config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30)).build();

    public AnthropicClient(AnthropicConfig config) { this.config = config; }

    /** 환경변수(ANTHROPIC_API_KEY)로 만드는 편의 팩토리. (5장 OpenAiClient.fromEnv()와 대칭) */
    public static AnthropicClient fromEnv() { return new AnthropicClient(AnthropicConfig.fromEnv()); }

    @Override public String name() { return "anthropic"; }

    @Override public LlmCapabilities capabilities() {
        // 명시 캐싱·thinking budget·웹검색·구조화출력 전부 지원
        return new LlmCapabilities(true, true, true, true);
    }

    // 반환타입은 Message.AssistantMessage로 한정한다. AssistantMessage는 Message의 중첩 타입이라
    // import agent.message.* 와일드카드로는 해석되지 않는다(5장 OpenAiClient도 같은 이유로 한정한다).
    @Override public Message.AssistantMessage create(LlmRequest r, CancellationToken c) {
        var acc = new AssistantMessageAccumulator();          // provider-중립(6장)
        try (var s = stream(r, c)) { for (StreamEvent e : s) acc.accept(e); }
        return acc.build();
    }

    @Override
    public EventStream<StreamEvent> stream(LlmRequest req, CancellationToken cancel) {
        ObjectNode body = toWireBody(req, true);
        HttpRequest httpReq = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/v1/messages"))
                .timeout(Duration.ofSeconds(120))
                .header("x-api-key", config.apiKey())
                .header("anthropic-version", config.anthropicVersion())
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                // betas: 베타 단계 기능을 켜는 provider-고유 헤더(OpenAI엔 없음).
                //   기본 cache_control은 anthropic-version만으로 동작한다(스코프 확장 등 일부 고급 기능은 별도 베타가 필요할 수 있다).
                //   베타 플래그는 변동되니 Anthropic 문서를 확인하고 필요할 때만 추가:
                //   .header("anthropic-beta", "<feature-flag>")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();

        return EventStreams.fromProducer(sink -> {
            HttpResponse<Stream<String>> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofLines());
            if (resp.statusCode() >= 400) {
                String errBody;
                try (Stream<String> err = resp.body()) {          // 오류 본문도 응답 스트림이다 — 성공 경로처럼 닫는다
                    errBody = err.collect(Collectors.joining("\n"));
                }
                sink.emit(new StreamEvent.ApiError(errBody, resp.statusCode()));
                return;
            }
            // Anthropic은 message_start/content_block_stop/message_stop 프레임을 직접 보내므로
            // OpenAI 경로(빈 스트림 ApiError 합성·MessageStart 합성·closeOpenBlocks)와 달리 합성 없이 그대로 흘린다.
            try (Stream<String> lines = resp.body()) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (cancel.isCancelled()) break;
                    if (!line.startsWith("data:")) continue;      // event:/빈 줄 무시
                    String json = line.substring("data:".length()).trim();
                    if (json.isEmpty()) continue;
                    dispatch(Json.read(json, JsonNode.class), sink);
                }
            }
        });
    }

    // ── 요청 번역 ────────────────────────────────────────────────
    private ObjectNode toWireBody(LlmRequest req, boolean streaming) {
        boolean caching = capabilities().promptCaching();
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("model", req.model()).put("max_tokens", req.maxTokens()).put("stream", streaming);

        if (!req.system().isEmpty()) {
            body.set("system", AnthropicWire.systemBlocks(req.system(), caching));   // top-level 분리
        }

        // thinking 활성 + capability 지원 시: temperature 미전송, budget 클램프(하한 1024, 상한 max_tokens-1)
        if (capabilities().thinkingBudget() && req.thinking() instanceof ThinkingConfig.Enabled en) {
            ObjectNode th = body.putObject("thinking");
            th.put("type", "enabled");
            th.put("budget_tokens", Math.min(Math.max(en.budgetTokens(), 1024), req.maxTokens() - 1));
        } else if (req.temperature() != null) {
            body.put("temperature", req.temperature());
        }

        body.set("messages", AnthropicWire.messages(req.messages(), capabilities()));

        if (!req.tools().isEmpty()) {
            var tools = body.putArray("tools");
            for (ToolSpec t : req.tools()) {
                ObjectNode wt = tools.addObject();
                wt.put("name", t.name()).put("description", t.description());
                wt.set("input_schema", t.inputSchema());          // OpenAI는 "parameters", Anthropic은 "input_schema"
            }
        }
        return body;
    }

    // ── SSE 분류: Anthropic 방언 → 공통 StreamEvent ────────────────
    private void dispatch(JsonNode node, EventStreams.Sink<StreamEvent> sink) {
        switch (node.path("type").asText()) {
            case "message_start" ->
                sink.emit(new StreamEvent.MessageStart(parseUsage(node.path("message").path("usage"))));
            case "content_block_start" ->
                sink.emit(new StreamEvent.BlockStart(node.path("index").asInt(),
                        initialBlock(node.path("content_block"))));
            case "content_block_delta" -> {
                StreamEvent.Delta d = parseDelta(node.path("delta"));
                if (d != null) sink.emit(new StreamEvent.BlockDelta(node.path("index").asInt(), d));
            }
            case "content_block_stop" -> sink.emit(new StreamEvent.BlockStop(node.path("index").asInt()));
            case "message_delta" -> sink.emit(new StreamEvent.MessageDelta(
                    parseUsage(node.path("usage")), node.path("delta").path("stop_reason").asText(null)));
            case "message_stop" -> sink.emit(new StreamEvent.MessageStop());
            // SSE 본문 내 오류 이벤트: HTTP 자체는 200이라 httpStatus=0으로 흘린다(HTTP 4xx/5xx는 위 stream()에서 상태코드 보존).
            case "error" -> sink.emit(new StreamEvent.ApiError(
                    node.path("error").path("message").asText("unknown"), 0));
            default -> { /* ping 등 무시 */ }
        }
    }

    private StreamEvent.Delta parseDelta(JsonNode d) {
        return switch (d.path("type").asText()) {
            case "text_delta" -> new StreamEvent.TextDelta(d.path("text").asText());
            case "thinking_delta" -> new StreamEvent.ThinkingDelta(d.path("thinking").asText());
            case "signature_delta" -> new StreamEvent.SignatureDelta(d.path("signature").asText());
            case "input_json_delta" -> new StreamEvent.InputJsonDelta(d.path("partial_json").asText());
            default -> null;
        };
    }

    private ContentBlock initialBlock(JsonNode b) {
        return switch (b.path("type").asText()) {
            case "tool_use" -> new ContentBlock.ToolUseBlock(
                    b.path("id").asText(), b.path("name").asText(), Json.MAPPER.createObjectNode());
            case "thinking" -> new ContentBlock.ThinkingBlock("", null);
            default -> new ContentBlock.TextBlock("");
        };
    }

    private Usage parseUsage(JsonNode u) {
        if (u == null || u.isMissingNode()) return Usage.EMPTY;
        return new Usage(
                u.path("input_tokens").asLong(0), u.path("output_tokens").asLong(0),
                u.path("cache_read_input_tokens").asLong(0),
                u.path("cache_creation_input_tokens").asLong(0), 0);
    }
}
