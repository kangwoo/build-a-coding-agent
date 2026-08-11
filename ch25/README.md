# 25장 코드 — MCP 연동: 외부 도구를 우리 Tool로 흡수

[`chapters/25-MCP-연동.md`](../../chapters/25-MCP-연동.md)의 산출물. 외부 **MCP(Model Context Protocol)** 서버에 stdio로 연결해 그 도구를 가져와, 7장 `Tool` 인터페이스로 감싸 레지스트리에 등록한다. 어댑터 하나면 12~24장 코드는 그것이 외부 도구인지 **모른다** — 외부 도구가 우리 도구와 똑같은 1급 시민이 된다. **확장성의 마지막 조각**이자 선택/심화 장이다.

```
ch25/                                         # ch24 위에 누적
└── agent-core/src/main/java/agent/mcp/       # ★ 새 패키지 — MCP 도구를 우리 Tool로 흡수
    ├── StdioMcpClient.java                   # ★ stdio JSON-RPC 클라이언트 — id 상관 맵·리더 스레드·initialize 핸드셰이크
    ├── McpTool.java                          # ★ MCP 도구 1개 → 우리 Tool 어댑터(이름 정규화·content[]→ContentBlock)
    └── McpTools.java                         # ★ tools/list 적재 → 레지스트리에 등록(수동 훅)

  agent-core/src/test/java/agent/mcp/
    ├── McpToolMappingTest.java               # ★ 이름 정규화·content[] 매핑·isError 전파·빈 스키마 fallback
    └── StdioMcpClientTest.java               # ★ sh 가짜 서버로 id 상관(알림 교란)·서버 사망 시 대기 요청 즉시 실패 검증

  ★ 신규
```

## MCP 흡수의 핵심

- **stdio JSON-RPC**: 서버를 자식 프로세스로 띄우고 stdin/stdout으로 JSON-RPC 2.0을 주고받는다(`StdioMcpClient`). 요청/응답을 `id`로 짝짓되, 서버가 알림(id 없는 메시지)을 섞어 보내므로 **별도 리더 스레드 + 상관(correlation) 맵**으로 처리한다. `connect`는 `initialize` 핸드셰이크까지 마친다.
- **Tool 어댑터**: `McpTool`이 MCP 도구 1개를 우리 `Tool<JsonNode, JsonNode>`로 감싼다. 입력은 임의 JSON이라 `JsonNode`로 그대로 통과시키고, 이름은 충돌을 막으려 **`mcp__<서버>__<도구>`** 로 정규화한다(영숫자/`_`/`-` 외는 `_`).
- **스키마 변환**: MCP는 `inputSchema`를 생략할 수 있다. 비면 Tool 기본값(record 가정) 대신 허용형 빈 객체 스키마(`{"type":"object"}`)로 대체한다 — `JsonNode`는 record가 아니라 기본값이 예외를 낸다.
- **결과 매핑**: 서버 응답의 `content[]`(text/image/그 외)를 우리 `ContentBlock`으로 변환하고, `isError` 플래그를 그대로 모델에 전파한다.
- **등록**: `McpTools.loadMcpTools`가 `tools/list`로 받은 도구를 레지스트리에 등록하는 **수동 훅**이다 — 이 책의 기본 에이전트는 MCP 서버를 자동 적재하지 않는다.

> 안전성(`isReadOnly`/`isConcurrencySafe`)은 알 수 없으니 보수적 기본값(false)으로 둔다 — 16장 권한·13장 직렬 실행이 그대로 안전하게 감싼다. 종료 시 자식·손자를 tree-kill 해 서버 프로세스를 남기지 않는다(15장과 동일).

## 빠른 시작

```bash
cd ch25
./gradlew test          # MCP 매핑·상관 검증(실제 MCP 서버 불필요·JDK 21·API 키 불필요)
```
