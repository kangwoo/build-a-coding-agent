# 12장 코드 — 에이전트 루프: while 루프 + sealed Transition

책 12장의 산출물. 5장 `LlmClient`(말하는 모델)와 7~11장 도구(행동하는 손)를 하나로 엮는 **에이전트 루프**. 모델 호출 → `tool_use` 추출 → 도구 실행 → `tool_result` 피드백 → 재호출을, async generator 없이 **평범한 `while(true)` 한 메서드**(`runLoop`)로 돈다. **이 책의 두 번째 마일스톤** — "이 파일 읽고 요약해줘" 한 마디에 모델이 *스스로* Read를 골라 실행하고 그 결과로 답하는 미니 에이전트.

```
ch12/                                          # ch11 위에 누적
├── agent-core/src/main/java/agent/
│   ├── engine/                                # ★ 새 패키지 — 루프와 그 출력 타입
│   │   ├── AgentEngine.java                   # ★ 대화 상태 소유 + runLoop(while). LlmClient 주입(provider 모름)
│   │   ├── AgentServices.java                 # ★ 교차 관심사 이음매(seam). 16·19·20장이 필드를 더함
│   │   ├── Transition.java                    # ★ sealed 종료 사유: Completed/MaxTurns/ModelError/Aborted
│   │   └── AgentEvent.java                    # ★ sealed 루프 출력: 텍스트델타/도구시작·완료/턴종료
│   ├── tool/builtin/BuiltinTools.java         # ★ 7~11장 도구 등록 팩토리(Read/Write/Edit/Glob/Grep/WebFetch)
│   └── message/Usage.java                     # ※ + plus() — 턴 '사이' 누적 합산(스트림 내 mergeCumulative와 구분)
└── agent-cli/src/main/java/agent/cli/
    ├── repl/Repl.java                         # ※ converse를 AgentEngine 이벤트 소비로 교체(history는 엔진이 소유)
    └── render/Renderer.java                   # ※ + toolStarted/toolFinished(도구 실행을 회색 한 줄로)

  agent-core/src/test/java/agent/engine/
    └── AgentEngineTest.java                   # ★ 가짜 모델(FakeModel)로 루프 검증 — 도구 실행→tool_result 불변식

  ★ 신규  ·  ※ 12장에서 변경
```

## 루프의 불변식

- **종료 판단은 `tool_use` 유무로**, `stop_reason`이 아니다(provider마다 믿을 만한 정도가 다름). 응답 *내용*에 `tool_use` 블록이 하나라도 있으면 한 턴 더, 없으면 `Completed`.
- **모든 `tool_use`에는 짝이 되는 `tool_result`**. 도구를 못 찾아도(`orElse`로 합성 오류 결과) 예외 없이 채운다 — 하나라도 빠지면 다음 호출이 400. 이 보장이 `runLoop` ④단계에 박혀 있다.
- **`tool_result`는 `user` 메시지에 담는다**(assistant가 만든 `tool_use`의 응답이지만 역할은 user — 4장).
- **최대 턴 가드**(`MAX_TURNS=20`): 모델의 무한 반복을 `MaxTurns`로 끊고 종료를 겉으로 드러낸다.
- **모델 주입(DI)**: 엔진은 어떤 provider인지 모른다(5장의 약속). 테스트는 `FakeModel`을 넣어 실제 API 없이 루프만 결정적으로 검증한다.

## 설계 이음매: 왜 지금 빈 `AgentServices`인가

`AgentServices`는 지금 필드가 하나도 없다. 이상해 보이지만 의도된 설계다. 권한(16장)·압축/비용(19장)·영속성(20장)을 생성자 인자로 하나씩 더하면 장마다 `AgentEngine` 시그니처가 바뀌고, 그 생성자를 부르는 모든 곳(REPL·테스트·23장 서브에이전트)이 깨진다. 미리 **묶음 하나**를 이음매로 파 두면, 이후엔 필드만 *더하고* `defaults()`와 REPL 조립부 **단 두 곳**만 손대면 된다. 16·19·20장의 변경이 한곳에 모인다.

## 빠른 시작

```bash
cd ch12
./gradlew test                                  # 가짜 모델로 루프 검증(JDK 21, API 키 불필요)
export OPENAI_API_KEY="sk-..."
./gradlew run --args="이 프로젝트의 build.gradle.kts 를 읽고 한 줄로 요약해줘"
```

`run` 하면 모델이 스스로 `Read`를 골라 실행하고 그 결과로 답한다 — **두 번째 마일스톤 달성.**
