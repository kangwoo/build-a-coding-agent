// agent-core/src/main/java/agent/mcp/McpTools.java
package agent.mcp;

import agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * MCP 서버를 부팅 시 연결해 도구를 레지스트리에 적재하는 수동 훅.
 * 이 책의 기본 에이전트는 MCP 서버를 자동 적재하지 않는다 —
 * 직접 부트스트랩 코드에서 호출해 쓰는 선택적 진입점이다.
 */
public final class McpTools {
    private McpTools() {}

    /** 부팅 시 MCP 서버 연결 → 도구 적재. 적재 실패 시 연결(프로세스)을 정리한다. */
    public static void loadMcpTools(ToolRegistry registry, String serverName,
                                    String command, List<String> args) throws Exception {
        StdioMcpClient client = StdioMcpClient.connect(command, args);
        try {
            for (JsonNode descriptor : client.listTools()) {
                registry.register(new McpTool(client, serverName, descriptor));   // 우리 Tool로 등록!
            }
        } catch (Exception e) {
            client.close();     // 도구 적재 실패 — 연결(프로세스)을 남기지 않는다
            throw e;
        }
    }
}
