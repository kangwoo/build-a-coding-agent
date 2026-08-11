# 19장 코드 — 컨텍스트 압축과 비용 추적

[`chapters/19-컨텍스트-압축과-비용.md`](../../chapters/19-컨텍스트-압축과-비용.md)의 산출물. 12장에 빈 채로 파 둔 이음매 `AgentServices`에 이번 장이 **압축(`ContextManager`)과 비용(`CostTracker`)** 둘을 필드로 채운다. 컨텍스트 윈도우 한계가 가까워지면 오래된 대화를 자동 요약해 세션을 끊지 않고, 누적 비용을 `BigDecimal`로 정확히 회계한다. 권한·맥락에 이어 **오래 견디는** 능력까지 붙은 **세 번째 마일스톤 — "실용 에이전트"** 다.

```
ch19/                                          # ch18 위에 누적
├── agent-core/src/main/java/agent/
│   ├── context/compact/                       # ★ 새 패키지 — 토큰 회계·압축
│   │   ├── TokenEstimator.java                # ★ 앵커(마지막 usage)+이후 rough 추정·4/3 보수 패딩
│   │   └── ContextManager.java                # ★ 임계치·마이크로/풀 압축·재귀 가드·서킷 브레이커·질문 재부착
│   ├── cost/CostTracker.java                  # ★ BigDecimal 누적 비용·모델별 집계·/cost 요약
│   └── engine/
│       ├── AgentServices.java                 # ※ + ContextManager·CostTracker(선택적, null이면 건너뜀)
│       └── AgentEngine.java                   # ※ 턴 시작에 압축 + 응답 뒤 비용 기록
└── agent-cli/src/main/java/agent/cli/
    ├── config/Prices.java                     # ★ OpenAI 단가표(gpt-5.4·mini) + 폴백
    └── repl/Repl.java                         # ※ 실제 윈도로 ContextManager·CostTracker 주입

  agent-core/src/test/java/agent/
  ├── context/compact/ContextManagerTest.java  # ★ 임계치·서킷 브레이커·마이크로/풀 압축
  ├── cost/CostTrackerTest.java                # ★ BigDecimal 정밀도·미상 모델 폴백 + 경고
  └── engine/AgentEngineTest.java              # ※ 압축 실패 시 이력 보존·서킷 브레이커 회귀(엔진 경유)

  ★ 신규  ·  ※ 19장에서 변경
```

## 컨텍스트 압축

- **앵커 + 추정**(`TokenEstimator`): 매번 전체를 토크나이저로 세지 않는다. 마지막 usage를 앵커(실측값)로 잡고 그 이후 메시지만 거칠게(문자÷4·이미지 2000) 추정한 뒤 4/3로 보수 패딩한다 — 과소추정으로 압축이 늦는 걸 막는다.
- **임계치는 컨텍스트 윈도우가 아니다**: 윈도우 − 요약 출력 예약(20K) − 버퍼(13K)의 **이중 차감**이다. 이걸 빠뜨리면 압축이 너무 늦거나(이미 넘침) 너무 이르다.
- **마이크로 vs 풀**: 마이크로 압축은 LLM 없이 오래된 `tool_result` 내용만 비우고(최근 4개 보존) 매 턴 가볍게 돈다. 풀 압축은 임계치 초과 시 대화 전체를 LLM으로 요약해 [경계 마커 + 요약 + 진행 중 질문 원문 재부착]으로 치환한다(턴 중간 발동에서 질문이 요약에 접히지 않게). 재부착할 질문은 관리자가 이력에서 휴리스틱으로 재파생하지 않는다 — 질문을 아는 엔진이 `fullCompact`의 인자로 넘기고, 턴 경계 호출자(`/compact` 커맨드 등)는 빈 값을 넘긴다.
- **두 안전장치**: 요약 호출은 루프를 거치지 않고 `model.create()`를 직접 부른다(재귀 가드). 또 요약은 기계적 작업이라 추론을 끄고(OpenAI면 `reasoning_effort` 미전송), 연속 3회 실패하면 서킷 브레이커로 압축을 멈춘다.

## 비용 추적

- **`BigDecimal` 누적**(`CostTracker`): 통화를 `double`로 누적하면 세션 내내 오차가 쌓인다. Mtok(백만 토큰)당 단가를 `BigDecimal`로 곱해 정확히 회계하고, usage는 모델별로 합산한다(`Usage::plus`).
- **단가표 + 폴백**(`Prices`): OpenAI 모델 단가(`gpt-5.4`·`gpt-5.4-mini`)를 표로 두고, 표에 없는 모델은 예외 대신 폴백 단가로 계산하되 "부정확" 플래그를 세워 알린다. OpenAI는 자동 프리픽스 캐싱이라 cache write 단가가 없어 0이다.
- **엔진 연결**: 둘 다 `AgentServices`의 *선택적* 필드다(`null`이면 해당 단계를 건너뜀). 엔진은 매 턴 시작에 압축을, 매 응답 뒤에 비용 기록을 끼운다. 생성자 시그니처는 그대로다.

## 빠른 시작

```bash
cd ch19
./gradlew test          # 압축 트리거·서킷 브레이커·BigDecimal 비용 검증(JDK 21, API 키 불필요)
export OPENAI_API_KEY="sk-..."
./gradlew run --args="이 저장소를 훑어보고 핵심 구조를 한눈에 정리해줘"
```

긴 대화에서도 토큰이 한계에 접근하면 자동 요약으로 세션이 끊기지 않고, 누적 비용이 정확히 추적된다 — **세 번째 마일스톤 달성.**
