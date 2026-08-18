# 24장 코드 — 멀티 Provider 완성(Anthropic·Gemini 합류)

책 24장의 산출물. 5장에서 세운 `LlmClient` SPI(OpenAI 기준) 위에 `AnthropicClient`·`GeminiClient`를 얹어, `--provider`만 바꾸면 같은 에이전트가 세 백엔드에서 돈다. 핵심은 기존 `agent-core`를 **한 줄도 안 고치고** 두 provider를 끼운다는 것이다 — 새 코드는 `llm/anthropic`·`llm/gemini` 구현뿐이고, 유일한 변경은 `agent-cli`의 `LlmClients` 팩토리 배선이다. 이 책의 **네 번째이자 마지막 마일스톤**(완성형)으로, `./gradlew run`으로 돌려본다.

```
ch24/                                          # ch23 위에 누적
├── agent-core/src/main/java/agent/llm/
│   ├── anthropic/
│   │   ├── AnthropicClient.java               # ★ 스트리밍 클라이언트 — SSE 이벤트형 → 공통 StreamEvent
│   │   ├── AnthropicConfig.java               # ★ 설정 — x-api-key·anthropic-version(record)
│   │   └── AnthropicWire.java                 # ★ JSON 와이어 — system 분리·tool_use/tool_result 블록·cache_control
│   └── gemini/
│       ├── GeminiClient.java                  # ★ 스트리밍 클라이언트 — ?key= URL·모델명 경로·message_start/stop 합성
│       ├── GeminiConfig.java                  # ★ 설정 — GEMINI_API_KEY/GOOGLE_API_KEY(record)
│       └── GeminiWire.java                    # ★ JSON 와이어 — contents·systemInstruction·functionCall/Response
└── agent-cli/src/main/java/agent/cli/bootstrap/
    └── LlmClients.java                        # ※ forProvider에 anthropic·gemini 배선(agent-core 무수정)

  agent-core/src/test/java/agent/llm/
  ├── anthropic/
  │   ├── AnthropicStreamingTest.java          # ★ SSE 누산 — 텍스트·tool_use·ApiError(상태코드·httpStatus=0)
  │   └── AnthropicWireTest.java               # ★ system 분리·tool_use/tool_result 블록·cache_control 1개
  └── gemini/
      ├── GeminiStreamingTest.java             # ★ text/functionCall 누산·usageMetadata→Usage
      └── GeminiWireTest.java                  # ★ contents·role·functionResponse 매핑

  ★ 신규  ·  ※ 24장에서 변경
```

## 한 인터페이스, 두 번역기

- **번역기 둘이면 끝**: 한 인터페이스 `LlmClient`(`stream`/`create`/`capabilities`)가 provider 차이를 격리한다. 각 구현이 할 일은 둘뿐이다 — 보낼 때 우리 `Message[]` → 회사 wire 포맷(Wire), 받을 때 회사 SSE → 공통 `StreamEvent`. 엔진은 `StreamEvent`만 보므로 provider를 모른다(5장의 약속).
- **provider별 Wire 매핑**: Anthropic은 system을 top-level 필드로 빼고 `tool_use`/`tool_result`를 content 블록에 둔다(OpenAI의 `role:"tool"` 메시지·`tool_calls[]`와 다름). Gemini는 `contents[]`·`systemInstruction`·`functionCall`/`functionResponse`로 옮긴다. 회사 단어(`cache_control`·`functionCall`·`tool_use_id`)는 각 패키지 안에만 산다.
- **capability 분기로 고유 기능 격리**: `capabilities()`(`promptCaching`/`thinkingBudget`)로 Anthropic의 `cache_control` 마커(요청당 정확히 1개 — system의 마지막 정적 `SystemBlock`)와 thinking budget(추론 시 temperature 미전송·budget을 `max_tokens-1`로 클램프)을 가둔다. Gemini는 둘 다 no-op이다.
- **불변식은 그대로**: 모든 `tool_use`에 짝이 되는 `tool_result`가 붙고, 스트리밍 usage는 누적이라 `+=`가 아니라 대입(`Usage.mergeCumulative`)이다. provider가 셋이어도 이 규칙은 provider-중립 누산기 한 곳에 산다.

## agent-core 무수정 = 이 장의 핵심

- **무수정 끼워넣기**: 엔진·도구·권한·압축·누산기 등 기존 `agent-core` 파일은 한 줄도 안 바뀐다. 새 provider 구현 파일만 `llm/anthropic`·`llm/gemini`에 더할 뿐이다. 코드 전체를 통틀어 유일한 변경은 `agent-cli`의 `LlmClients.forProvider`에 anthropic·gemini 두 케이스를 채운 배선 한 곳이다 — 5장 추상화가 옳았다는 증거이자, 이 장이 증명하려는 명제다.
- **추상화가 새면 즉시 보인다**: 새 provider를 붙이는데 엔진이나 도구를 고쳐야 했다면 추상화가 샌 것이다. 번역기 둘 + capability 분기로 끝나야 정상이다.

## 빠른 시작

```bash
cd ch24
./gradlew test          # 와이어/SSE 번역 검증 — API 키 없이 통과(JDK 21)
export ANTHROPIC_API_KEY="sk-ant-..."   # 또는 OPENAI_API_KEY / GEMINI_API_KEY
./gradlew run --args="--provider anthropic"   # 또는 --provider gemini / openai
```

`--provider`로 두뇌만 갈아끼우면 같은 도구·루프·권한·압축이 그대로 돈다 — **네 번째이자 마지막 마일스톤, 멀티 provider 완성형 달성.**
