package agent.llm.gemini;

import agent.exec.CancellationToken;
import agent.llm.*;
import agent.message.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.stream.*;

public final class GeminiClient implements LlmClient {

    private final GeminiConfig config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30)).build();

    public GeminiClient(GeminiConfig config) { this.config = config; }

    /** 환경변수(GEMINI_API_KEY/GOOGLE_API_KEY)로 만드는 편의 팩토리. */
    public static GeminiClient fromEnv() { return new GeminiClient(GeminiConfig.fromEnv()); }

    @Override public String name() { return "gemini"; }

    @Override public LlmCapabilities capabilities() {
        return new LlmCapabilities(false, false, false, true);   // 명시 캐싱·thinking budget 없음
    }

    @Override public Message.AssistantMessage create(LlmRequest r, CancellationToken c) {
        var acc = new AssistantMessageAccumulator();          // provider-중립(6장)
        try (var s = stream(r, c)) { for (StreamEvent e : s) acc.accept(e); }
        return acc.build();
    }

    @Override
    public EventStream<StreamEvent> stream(LlmRequest req, CancellationToken cancel) {
        ObjectNode body = toWireBody(req);
        // 엔드포인트: /v1beta/models/{model}:streamGenerateContent?alt=sse
        //   API 키는 x-goog-api-key 헤더로 전달한다(자체 헤더 — OpenAI의 Authorization: Bearer와 다르다).
        String url = config.baseUrl() + "/v1beta/models/" + req.model()
                + ":streamGenerateContent?alt=sse";
        HttpRequest httpReq = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .header("x-goog-api-key", config.apiKey())
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
            // Gemini는 message_start/stop 프레임이 없으므로 OpenAI 경로처럼 우리가 합성한다.
            sink.emit(new StreamEvent.MessageStart(Usage.EMPTY));
            var state = new TranslateState();

            try (Stream<String> lines = resp.body()) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (cancel.isCancelled()) break;
                    if (!line.startsWith("data:")) continue;      // 빈 줄 무시
                    String json = line.substring("data:".length()).trim();
                    if (json.isEmpty()) continue;
                    translate(Json.read(json, JsonNode.class), state, sink);
                }
            }

            if (cancel.isCancelled()) return;
            // 열린 블록을 닫고 최종 usage·stop_reason을 합성한다.
            state.closeOpenBlocks(sink);
            sink.emit(new StreamEvent.MessageDelta(state.usage, state.stopReason));
            sink.emit(new StreamEvent.MessageStop());
        });
    }

    // ── SSE 분류: Gemini 방언 → 공통 StreamEvent ──────────────────
    //   candidates[0].content.parts[] 순회
    //     part.text         → BlockDelta(TextDelta)               (텍스트는 index 0)
    //     part.functionCall → BlockStart(ToolUseBlock) + BlockDelta(InputJsonDelta(완성 JSON 한 조각))  (도구는 index 1+)
    //   usageMetadata → Usage, finishReason → stopReason
    private void translate(JsonNode chunk, TranslateState st, EventStreams.Sink<StreamEvent> sink) {
        JsonNode usage = chunk.path("usageMetadata");
        if (usage.isObject()) {
            st.usage = new Usage(
                    usage.path("promptTokenCount").asLong(0),
                    usage.path("candidatesTokenCount").asLong(0),
                    0, 0, 0);
        }

        JsonNode candidate = chunk.path("candidates").path(0);
        String finish = candidate.path("finishReason").asText(null);
        if (finish != null) st.stopReason = mapStop(finish);

        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.has("functionCall")) {
                // 도구 호출: 인자(args)는 완성된 JSON 객체로 한 번에 온다.
                // 우리 InputJsonDelta(조각 누적) 경로로 통일하려고 전체 JSON을 "한 조각"으로 흘린다.
                JsonNode fc = part.path("functionCall");
                int idx = ++st.toolIndex;                          // 0은 텍스트 몫, 도구는 1+
                sink.emit(new StreamEvent.BlockStart(idx, new ContentBlock.ToolUseBlock(
                        "call_" + idx, fc.path("name").asText(), Json.MAPPER.createObjectNode())));
                JsonNode args = fc.path("args");
                String argsJson = args.isMissingNode() ? "{}" : Json.write(args);
                sink.emit(new StreamEvent.BlockDelta(idx, new StreamEvent.InputJsonDelta(argsJson)));
                st.openTools.add(idx);
            } else if (part.has("text")) {
                if (!st.startedText) {
                    sink.emit(new StreamEvent.BlockStart(0, new ContentBlock.TextBlock("")));
                    st.startedText = true;
                }
                sink.emit(new StreamEvent.BlockDelta(0, new StreamEvent.TextDelta(part.path("text").asText())));
            }
        }
    }

    /** 스트림 동안 우리가 연 블록들을 끝에서 닫아 BlockStop을 합성한다(6장 OpenAI 방식과 동일). */
    private static final class TranslateState {
        boolean startedText = false;
        int toolIndex = 0;
        final java.util.List<Integer> openTools = new java.util.ArrayList<>();
        Usage usage = Usage.EMPTY;
        String stopReason;

        void closeOpenBlocks(EventStreams.Sink<StreamEvent> sink) {
            if (startedText) sink.emit(new StreamEvent.BlockStop(0));
            for (int idx : openTools) sink.emit(new StreamEvent.BlockStop(idx));
        }
    }

    private static String mapStop(String f) {
        return switch (f) {
            case "STOP" -> "end_turn";      // 도구 호출 시에도 STOP을 주는 경우가 많다
            case "MAX_TOKENS" -> "max_tokens";
            default -> f;                   // SAFETY 등은 그대로 통과 — 이상 종료를 정상으로 위장하지 않는다
        };
    }

    // ── 요청 번역: 중립 LlmRequest → Gemini generateContent JSON ───
    private ObjectNode toWireBody(LlmRequest req) {
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.set("contents", GeminiWire.contents(req.messages()));

        if (!req.system().isEmpty()) {
            body.set("systemInstruction", GeminiWire.systemInstruction(req.systemText()));
        }

        ObjectNode gen = body.putObject("generationConfig");
        gen.put("maxOutputTokens", req.maxTokens());
        // thinking budget(capabilities=false)·temperature 처리: Gemini는 추론 모드 분기가 없어 temperature만 전달.
        if (req.temperature() != null) gen.put("temperature", req.temperature());

        if (!req.tools().isEmpty()) {
            body.set("tools", GeminiWire.tools(req.tools()));
        }
        return body;
    }
}
