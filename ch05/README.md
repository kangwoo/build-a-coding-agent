# 5장 코드 — LLM Provider 추상화

[`chapters/05-LLM-Provider-추상화.md`](../../chapters/05-LLM-Provider-추상화.md)의 산출물. 2장의 `OpenAiChat` 스파이크를 **provider-중립 `LlmClient` SPI**와 그 기준 구현 **`OpenAiClient`**로 승격한다. REPL이 회사별 API가 아니라 `LlmClient` 인터페이스 하나에만 의존한다(비스트리밍 `create()`; 스트리밍은 6장).

```
ch05/
├── gradle/libs.versions.toml      # + wiremock(5장 테스트)
├── agent-core/
│   ├── build.gradle.kts           # + testImplementation(wiremock)
│   └── src/main/java/agent/
│       ├── exec/CancellationToken.java   # 최소 취소 토큰(14장에서 확장)
│       ├── llm/
│       │   ├── LlmClient.java            # SPI — create()/capabilities()/name()
│       │   ├── LlmRequest.java           # provider 중립 요청
│       │   ├── ToolSpec.java             # 도구 명세(7·8장에서 채워짐)
│       │   ├── ThinkingConfig.java       # sealed — Disabled/Enabled(budgetTokens)
│       │   ├── LlmCapabilities.java      # provider별 기능 유무
│       │   └── openai/
│       │       ├── OpenAiConfig.java     # apiKey/baseUrl (fromEnv)
│       │       └── OpenAiClient.java     # 중립 요청 ↔ /v1/chat/completions 번역·조립
│       └── message/ …                    # 4장 그대로
│   └── src/test/java/agent/llm/openai/
│       └── OpenAiClientTest.java         # WireMock으로 번역·조립 검증(키 불필요)
└── agent-cli/
    └── src/main/java/agent/cli/
        ├── bootstrap/
        │   ├── LlmClients.java           # provider 이름 → LlmClient 팩토리
        │   └── CliApplication.java       # new Repl(session, provider)
        └── repl/Repl.java                # OpenAiChat 직접 호출 → LlmClient.create()
                                          # (4장 Wire 어댑터는 삭제됨)
```

> **4장 대비 변화**: `agent.cli.repl.Wire`(임시 Map 어댑터)는 삭제됐다 — 새 `Repl`이 `LlmClient`에 `List<Message>`를 직접 넘긴다. 2장 `agent.warmup`(OpenAiChat·Main)과 그 테스트는 역사적 스파이크로 남겨 둔다(아직 컴파일·테스트 통과).

## 테스트 (API 키 불필요)

```bash
./gradlew test          # agent-core(6) + agent-cli(5) = 11개 테스트
```

- `agent-core`: 메시지 도메인 라운드트립·누적(4) + **`OpenAiClientTest`(2)** — WireMock으로 가짜 Chat Completions 응답을 띄워 **usage 정규화**(`prompt_tokens`/`completion_tokens` → input/output), **finish_reason 매핑**(`stop` → `end_turn`), **텍스트 조립**(`message.content` → `TextBlock`), **HTTP 오류 표면화**(4xx → 예외)를 검증한다.
- `agent-cli`: `Renderer`(3) + 2장 `OpenAiChat` 로컬 스텁 왕복(2).

## 대화 실행 (API 키 필요)

```bash
export OPENAI_API_KEY="sk-..."
./gradlew run                       # 기본 --provider=openai
# › 자바에서 record가 뭐야? 한 문장으로.
# record는 불변 데이터를 담는 간결한 클래스 선언 문법입니다.   ← 답을 다 받은 뒤 한꺼번에
```

이제 대화가 **provider-중립 `LlmClient`**를 통해 굴러간다. 백엔드 교체(24장)는 `LlmClients.forProvider`의 `switch` 한 줄과 새 구현체만으로 끝난다. 다만 긴 응답일수록 화면이 멈췄다가 한꺼번에 쏟아진다 — 그 멈춤을 없애는 스트리밍은 6장.

## 참고

- 회사별 형식(`choices`/`tool_calls`/`prompt_tokens`/`finish_reason`)은 `agent.llm.openai` 안에만 산다. 엔진·REPL은 공통 타입(`AssistantMessage`/`ContentBlock`/`Usage`)만 본다.
- `OpenAiClient.buildHttpRequest`/`toWireBody`는 `boolean streaming` 인자를 미리 받아 둔다 — 6장 스트리밍이 같은 메서드를 `stream:true`로 재사용한다.
- 기본 모델 `gpt-5.4-mini`는 최신 OpenAI 모델 id로 교체 가능(부록 A.4).
