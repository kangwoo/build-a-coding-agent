package agent.mcp;

import agent.message.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisabledOnOs(OS.WINDOWS)
class StdioMcpClientTest {

    static boolean sh() {
        try { return new ProcessBuilder("sh", "-c", "true").start().waitFor() == 0; }
        catch (Exception e) { return false; }
    }

    @TempDir Path dir;

    /** stdin을 줄 단위로 읽어 JSON-RPC로 답하는 가짜 서버. tools/list 응답 앞에 id 없는 알림을 끼워 짝 맞춤을 교란한다. */
    private static final String SERVER = """
            while IFS= read -r line; do
              id=$(printf '%s' "$line" | sed -n 's/.*"id":\\([0-9]*\\).*/\\1/p')
              case "$line" in
                *'"method":"initialize"'*)
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2024-11-05"}}\\n' "$id" ;;
                *'"method":"tools/list"'*)
                  printf '{"jsonrpc":"2.0","method":"notifications/message","params":{"level":"info"}}\\n'
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"echo"}]}}\\n' "$id" ;;
                *'"method":"tools/call"'*)
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"pong"}],"isError":false}}\\n' "$id" ;;
              esac
            done
            """;

    @Test @EnabledIf("sh")
    void correlates_responses_by_id_even_with_notifications_interleaved() throws Exception {
        Path script = dir.resolve("server.sh");
        Files.writeString(script, SERVER);

        // connect()가 initialize 왕복(id=1)까지 끝낸다 — 여기 도달했으면 핸드셰이크 상관은 이미 성립.
        try (var client = StdioMcpClient.connect("sh", List.of(script.toString()))) {
            JsonNode tools = client.listTools();               // 응답 직전에 id 없는 알림이 끼어든다
            assertThat(tools.get(0).path("name").asText()).isEqualTo("echo");

            JsonNode result = client.callTool("echo", Json.MAPPER.createObjectNode());
            assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("pong");
        }
    }

    /** initialize에는 답하고, tools/call을 받으면 응답 없이 죽는 가짜 서버. */
    private static final String DYING_SERVER = """
            while IFS= read -r line; do
              id=$(printf '%s' "$line" | sed -n 's/.*"id":\\([0-9]*\\).*/\\1/p')
              case "$line" in
                *'"method":"initialize"'*)
                  printf '{"jsonrpc":"2.0","id":%s,"result":{}}\\n' "$id" ;;
                *'"method":"tools/call"'*)
                  exit 0 ;;
              esac
            done
            """;

    @Test @EnabledIf("sh")
    void server_death_fails_pending_requests_immediately() throws Exception {
        Path script = dir.resolve("dying.sh");
        Files.writeString(script, DYING_SERVER);

        try (var client = StdioMcpClient.connect("sh", List.of(script.toString()))) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> client.callTool("x", Json.MAPPER.createObjectNode()))
                    .hasRootCauseMessage("MCP 서버 연결 종료");   // readLoop가 EOF에서 대기 요청에 전파
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).isLessThan(10_000);          // 30초 타임아웃까지 잡아 두지 않는다
        }
    }
}
