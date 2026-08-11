// agent-core/src/main/java/agent/mcp/StdioMcpClient.java
package agent.mcp;

import agent.message.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class StdioMcpClient implements AutoCloseable {

    private final Process proc;
    private final BufferedWriter stdin;
    private final AtomicLong idGen = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    private StdioMcpClient(Process proc) {
        this.proc = proc;
        this.stdin = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
        Thread.ofVirtual().name("mcp-reader").start(this::readLoop);   // 응답/알림 수신 루프
    }

    /** 서버를 spawn하고 initialize 핸드셰이크까지 마친다. 실패하면 프로세스를 정리한다. */
    public static StdioMcpClient connect(String command, List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>(); cmd.add(command); cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        var client = new StdioMcpClient(p);
        try {
            ObjectNode init = Json.MAPPER.createObjectNode();
            init.put("protocolVersion", "2024-11-05");
            init.putObject("capabilities");
            init.putObject("clientInfo").put("name", "coding-agent").put("version", "0.1");
            client.request("initialize", init);                 // 응답 대기
            client.notification("notifications/initialized", Json.MAPPER.createObjectNode());
            return client;
        } catch (Exception e) {
            client.close();     // 핸드셰이크 실패/타임아웃 — spawn한 프로세스를 남기지 않는다
            throw e;
        }
    }

    public JsonNode listTools() throws Exception {
        return request("tools/list", Json.MAPPER.createObjectNode()).path("tools");
    }

    public JsonNode callTool(String name, JsonNode arguments) throws Exception {
        ObjectNode params = Json.MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        return request("tools/call", params);
    }

    // ── JSON-RPC ─────────────────────────────────────────
    private JsonNode request(String method, JsonNode params) throws Exception {
        long id = idGen.getAndIncrement();
        var fut = new CompletableFuture<JsonNode>();
        pending.put(id, fut);
        try {
            ObjectNode msg = Json.MAPPER.createObjectNode();
            msg.put("jsonrpc", "2.0").put("id", id).put("method", method).set("params", params);
            send(msg);
            JsonNode response = fut.get(30, TimeUnit.SECONDS);
            if (response.has("error"))
                throw new IOException("MCP 오류: " + response.path("error").path("message").asText());
            return response.path("result");
        } finally {
            pending.remove(id);   // 타임아웃/실패 시 엔트리를 남기지 않는다(정상 응답은 readLoop가 제거)
        }
    }

    private void notification(String method, JsonNode params) throws Exception {
        ObjectNode msg = Json.MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0").put("method", method).set("params", params);   // id 없음
        send(msg);
    }

    private synchronized void send(ObjectNode msg) throws IOException {
        stdin.write(Json.write(msg)); stdin.write("\n"); stdin.flush();          // 줄 구분
    }

    private void readLoop() {
        try (var r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode msg = Json.read(line, JsonNode.class);
                if (msg.has("id") && !msg.path("id").isNull()) {
                    var fut = pending.remove(msg.path("id").asLong());
                    if (fut != null) fut.complete(msg);                          // 응답 → 짝 맞춤
                    // 짝이 없는 id 응답도 조용히 버린다(방어적).
                }
                // id 없는 메시지(알림)는 무시(필요 시 별도 처리)
            }
        } catch (IOException ignored) { /* 프로세스 종료 시 정상 */
        } finally {
            // 서버가 죽으면 대기 중인 요청을 즉시 실패시킨다(30초 타임아웃까지 잡아 두지 않게).
            var dead = new IOException("MCP 서버 연결 종료");
            for (var fut : pending.values()) fut.completeExceptionally(dead);
            pending.clear();
        }
    }

    @Override public void close() {
        proc.descendants().forEach(ProcessHandle::destroyForcibly);
        proc.destroyForcibly();
    }
}
