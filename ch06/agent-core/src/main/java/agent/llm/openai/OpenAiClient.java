package agent.llm.openai;

import agent.exec.CancellationToken;
import agent.llm.*;
import agent.message.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class OpenAiClient implements LlmClient {

    private final OpenAiConfig config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public OpenAiClient(OpenAiConfig config) { this.config = config; }

    /** 환경변수(OPENAI_API_KEY)로 만드는 편의 팩토리. (24장 AnthropicClient.fromEnv()와 대칭) */
    public static OpenAiClient fromEnv() { return new OpenAiClient(OpenAiConfig.fromEnv()); }

    @Override public String name() { return "openai"; }

    @Override public LlmCapabilities capabilities() {
        // 캐싱은 자동 프리픽스(명시 마커 없음) → promptCaching=false.
        // 추론은 reasoning_effort 단계라 토큰 budget=false. (effort 매핑은 toWireBody)
        return new LlmCapabilities(false, false, false, true);
    }

    @Override
    public Message.AssistantMessage create(LlmRequest request, CancellationToken cancel) {
        HttpRequest httpReq = buildHttpRequest(request, false);     // stream:false
        try {
            HttpResponse<String> resp =
                    http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new RuntimeException("API " + resp.statusCode() + ": " + resp.body());
            }
            return parseResponse(Json.read(resp.body(), JsonNode.class));
        } catch (java.io.IOException e) {
            throw new RuntimeException("OpenAI 호출 실패", e);
        } catch (InterruptedException e) {
            // 인터럽트는 오류가 아니라 취소다 — 오류로 감싸면 19장 압축 서킷 브레이커가 실패로 집계한다.
            Thread.currentThread().interrupt();
            throw new StreamCancelledException("create() 대기 중 인터럽트됨");
        }
    }

    @Override
    public EventStream<StreamEvent> stream(LlmRequest request, CancellationToken cancel) {
        HttpRequest httpReq = buildHttpRequest(request, true);     // 5장 메서드를 stream:true로 재사용

        return EventStreams.fromProducer(sink -> {
            HttpResponse<Stream<String>> resp =
                    http.send(httpReq, HttpResponse.BodyHandlers.ofLines());

            if (resp.statusCode() >= 400) {
                String errBody;
                try (Stream<String> err = resp.body()) {          // 오류 본문도 응답 스트림이다 — 성공 경로처럼 닫는다
                    errBody = err.collect(Collectors.joining("\n"));
                }
                sink.emit(new StreamEvent.ApiError(errBody, resp.statusCode()));
                return;
            }

            // OpenAI엔 message_start 프레임이 없으므로 우리가 합성한다.
            sink.emit(new StreamEvent.MessageStart(Usage.EMPTY));
            var state = new TranslateState();

            try (Stream<String> lines = resp.body()) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (cancel.isCancelled()) break;
                    if (!line.startsWith("data:")) continue;          // event:/빈 줄 무시
                    String data = line.substring("data:".length()).trim();
                    if (data.equals("[DONE]")) break;                 // OpenAI 종료 표시
                    if (data.isEmpty()) continue;
                    translate(Json.read(data, JsonNode.class), state, sink);
                }
            }

            // 빈 스트림: 블록도 stop_reason도 없이 끝나면 보통 중간 프록시 실패다 → ApiError로 드러낸다.
            // (블록이 0개라도 finish_reason을 받았다면 합법적 빈 응답이므로 오류로 보지 않는다.)
            if (cancel.isCancelled()) return;
            if (!state.startedText && state.startedTools.isEmpty() && state.stopReason == null) {
                sink.emit(new StreamEvent.ApiError("빈 스트림(청크 없음)", resp.statusCode()));
                return;
            }

            // 끝에서 message_delta(최종 usage·stop_reason) + message_stop을 합성한다.
            state.closeOpenBlocks(sink);
            sink.emit(new StreamEvent.MessageDelta(state.usage, state.stopReason));
            sink.emit(new StreamEvent.MessageStop());
        });
    }

    // ── SSE 분류: OpenAI 방언 → 공통 StreamEvent ──────────────────────
    //   choices[0].delta.content       → 텍스트(블록 0)
    //   choices[0].delta.tool_calls[]  → 도구 호출(블록 1+, 7~12장)
    //   별도 청크의 usage              → MessageDelta로 누적
    //   choices[0].finish_reason       → stop_reason

    private void translate(JsonNode chunk, TranslateState st, EventStreams.Sink<StreamEvent> sink) {
        JsonNode usage = chunk.path("usage");
        if (usage.isObject()) {
            st.usage = new Usage(
                    usage.path("prompt_tokens").asLong(0),
                    usage.path("completion_tokens").asLong(0),
                    0, 0, 0);
        }

        JsonNode choice = chunk.path("choices").path(0);
        String finish = choice.path("finish_reason").asText(null);
        if (finish != null) st.stopReason = mapStop(finish);          // 5장 mapStop 재사용

        JsonNode delta = choice.path("delta");

        // 1) 텍스트 조각 → 블록 0
        if (delta.hasNonNull("content")) {
            if (!st.startedText) {
                sink.emit(new StreamEvent.BlockStart(0, new ContentBlock.TextBlock("")));
                st.startedText = true;
            }
            sink.emit(new StreamEvent.BlockDelta(0, new StreamEvent.TextDelta(delta.get("content").asText())));
        }

        // 2) 도구 호출 조각 → 블록 1+ (index 충돌 방지로 +1). 7~12장에서 본격 사용.
        for (JsonNode tc : delta.path("tool_calls")) {
            int idx = tc.path("index").asInt() + 1;               // 0은 텍스트 몫
            if (tc.path("function").hasNonNull("name") && st.startedTools.add(idx)) {
                sink.emit(new StreamEvent.BlockStart(idx, new ContentBlock.ToolUseBlock(
                        tc.path("id").asText(),
                        tc.path("function").path("name").asText(),
                        Json.MAPPER.createObjectNode())));
            }
            String argsPart = tc.path("function").path("arguments").asText("");
            if (!argsPart.isEmpty()) {
                sink.emit(new StreamEvent.BlockDelta(idx, new StreamEvent.InputJsonDelta(argsPart)));
            }
        }
    }

    /** 스트림 동안 우리가 연 블록들을 끝에서 닫아 BlockStop을 합성한다. */
    private static final class TranslateState {
        boolean startedText = false;
        final Set<Integer> startedTools = new HashSet<>();
        Usage usage = Usage.EMPTY;
        String stopReason;

        void closeOpenBlocks(EventStreams.Sink<StreamEvent> sink) {
            if (startedText) sink.emit(new StreamEvent.BlockStop(0));
            for (int idx : startedTools) sink.emit(new StreamEvent.BlockStop(idx));
        }
    }

    // ── 응답 조립: OpenAI Chat Completions JSON → 우리 AssistantMessage ──
    //   choices[0].message.content      → TextBlock
    //   choices[0].message.tool_calls[] → ToolUseBlock (7~12장에서 본격 사용)
    //   usage.prompt_tokens/completion_tokens → Usage(input/output)
    //   choices[0].finish_reason        → stopReason

    private Message.AssistantMessage parseResponse(JsonNode root) {
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");

        List<ContentBlock> blocks = new ArrayList<>();

        String text = message.path("content").asText("");
        if (!text.isEmpty()) {
            blocks.add(new ContentBlock.TextBlock(text));
        }

        for (JsonNode tc : message.path("tool_calls")) {            // 7~12장에서 채워짐
            JsonNode fn = tc.path("function");
            JsonNode input = parseArgs(fn.path("arguments").asText(""));
            blocks.add(new ContentBlock.ToolUseBlock(
                    tc.path("id").asText(),
                    fn.path("name").asText(),
                    input));
        }

        JsonNode usage = root.path("usage");
        Usage u = new Usage(
                usage.path("prompt_tokens").asLong(0),
                usage.path("completion_tokens").asLong(0),
                0, 0, 0);

        String stop = mapStop(choice.path("finish_reason").asText("stop"));
        return Message.AssistantMessage.of(blocks, u, stop);
    }

    private JsonNode parseArgs(String args) {
        if (args == null || args.isBlank()) return Json.MAPPER.createObjectNode();
        return Json.read(args, JsonNode.class);
    }

    private static String mapStop(String f) {
        return switch (f) {
            case "stop" -> "end_turn";
            case "tool_calls" -> "tool_use";
            case "length" -> "max_tokens";
            default -> f;                   // content_filter 등은 그대로 통과 — 이상 종료를 정상으로 위장하지 않는다
        };
    }

    // ── 요청 번역: 중립 LlmRequest → OpenAI Chat Completions JSON ──────

    private HttpRequest buildHttpRequest(LlmRequest req, boolean streaming) {
        ObjectNode body = toWireBody(req, streaming);
        return HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("authorization", "Bearer " + config.apiKey())
                .header("content-type", "application/json")
                .header("accept", streaming ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
    }

    private ObjectNode toWireBody(LlmRequest req, boolean streaming) {
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("model", req.model());
        // 최신 OpenAI 모델은 max_tokens 대신 max_completion_tokens를 받는다(max_tokens는 2024-09 deprecated).
        body.put("max_completion_tokens", req.maxTokens());
        body.put("stream", streaming);
        if (req.temperature() != null) {
            body.put("temperature", req.temperature());
        }
        // 추론: 중립 ThinkingConfig → OpenAI reasoning_effort 단계로 접는다.
        if (req.thinking() instanceof ThinkingConfig.Enabled en) {
            int b = en.budgetTokens();
            body.put("reasoning_effort", b <= 4096 ? "low" : b <= 16384 ? "medium" : "high");
        }

        body.set("messages", toWireMessages(req.system(), req.messages()));

        if (!req.tools().isEmpty()) {                             // 7~12장에서 채워짐
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec t : req.tools()) {
                ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
                fn.put("name", t.name());
                fn.put("description", t.description());
                fn.set("parameters", t.inputSchema());
            }
        }

        // 스트리밍일 때만: 마지막 청크에 usage를 받으려면 명시적으로 요청해야 한다(6장).
        if (streaming) body.putObject("stream_options").put("include_usage", true);
        return body;
    }

    /**
     * 우리 Message[] → OpenAI messages[].
     * system은 첫 메시지(role:"system")로 prepend.
     * assistant의 tool_use는 tool_calls[]로, user의 tool_result는 role:"tool" 메시지로 펼친다(하나→여럿). 7~12장에서 채워진다.
     */
    private ArrayNode toWireMessages(String system, List<Message> messages) {
        ArrayNode arr = Json.MAPPER.createArrayNode();
        if (system != null && !system.isBlank()) {
            arr.addObject().put("role", "system").put("content", system);
        }
        for (Message m : messages) {
            if (m instanceof Message.NoticeMessage) continue;     // 내부 알림은 전송 안 함

            if (m instanceof Message.AssistantMessage) {          // 텍스트 + tool_calls
                ObjectNode wm = arr.addObject().put("role", "assistant");
                wm.put("content", flattenText(m.content()));
                ArrayNode calls = null;
                for (ContentBlock b : m.content()) {
                    if (b instanceof ContentBlock.ToolUseBlock u) {
                        if (calls == null) calls = wm.putArray("tool_calls");
                        ObjectNode call = calls.addObject();
                        call.put("id", u.id()).put("type", "function");
                        call.putObject("function")
                                .put("name", u.name())
                                .put("arguments", Json.write(u.input()));   // arguments는 JSON 문자열
                    }
                }
            } else {                                              // user: tool_result는 role:"tool"로 분리
                boolean hadResult = false;
                for (ContentBlock b : m.content()) {
                    if (b instanceof ContentBlock.ToolResultBlock r) {
                        hadResult = true;
                        arr.addObject()
                                .put("role", "tool")
                                .put("tool_call_id", r.toolUseId())
                                .put("content", flattenText(r.content()));
                    }
                }
                String text = flattenText(m.content());
                boolean toolResultOnly = hadResult && text.isEmpty();   // tool_result뿐인 메시지 — user로 보낼 것이 없다
                if (!toolResultOnly) {                            // 혼재 텍스트도 버리지 않는다 —
                    arr.addObject().put("role", "user").put("content", text);   // tool 메시지들 '뒤'에 user로(24장 Anthropic과 대칭)
                }
            }
        }
        return arr;
    }

    private String flattenText(List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof ContentBlock.TextBlock t) sb.append(t.text());
        }
        return sb.toString();
    }
}
