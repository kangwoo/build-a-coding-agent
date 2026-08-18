# 13장 코드 — 도구 오케스트레이션: 안전=병렬·위험=직렬, 순서는 보존

책 13장의 산출물. 12장 루프는 한 턴의 여러 `tool_use`를 하나씩 직렬로 실행했다. 이 장은 그 ④단계를 `ToolOrchestrator`로 교체해 "안전한 건 병렬, 위험한 건 단독 직렬"로 분배하되, 결과는 **모델이 부른 순서 그대로** 돌려준다. 병렬로 도구가 돌기 시작하므로, 14장의 계층적 취소 트리가 이 실행 위에 올라간다.

```
ch13/                                          # ch12 위에 누적
└── agent-core/src/main/java/agent/
    ├── exec/ToolOrchestrator.java             # ★ 안전=병렬·위험=직렬 분배 + 인덱스로 순서 보존
    └── engine/AgentEngine.java                # ※ + 루프 ④를 오케스트레이터에 위임

  agent-core/src/test/java/agent/exec/
    └── ToolOrchestratorTest.java              # ★ 순서 보존·동시성 상한·위험 도구 단독·미지 도구 오류 짝

  ★ 신규  ·  ※ 13장에서 변경
```

## 두 규칙: 병렬성과 순서 보존

- **안전한 것만 병렬.** 연속된 안전(concurrency-safe) 도구를 한 배치로 묶어 병렬, 위험 도구(Write/Edit/Bash)는 각자 단독 직렬로 가른다 — 순서는 유지한 채.
- **fail-closed.** `isConcurrencySafe` 기본값이 `false`(7장)라 모르면 직렬이다. 안전성 판정 중 역직렬화가 실패해도 `false`(직렬)로 떨군다. 의심스러우면 안전하게 간다.
- **결과는 호출 순서대로(in-order).** 병렬은 끝나는 순서가 뒤죽박죽이라(작은 파일이 먼저 끝남), `results[원래위치] = 결과`로 인덱스에 꽂아 순서를 보존한다. `tool_use_id` 매칭보다 단순·결정적이다.
- **동시성 상한.** 모델이 한 턴에 도구 50개를 부를 수 있다. `Semaphore`로 동시 실행을 제한(기본 10)해 시스템을 보호한다.
- **progress는 별도 채널.** `Listener.started/finished`를 결과와 무관하게 즉시 흘려, 오래 걸리는 도구가 도는 동안에도 화면이 멈춘 듯 보이지 않는다. 가상 스레드 + `f.get()`의 happens-before로 배열 읽기는 안전하다.

## 두 군데 함정(AgentEngine 연결)

- **초기화 순서.** `orchestrator`를 필드 이니셜라이저로 두면 `this.tools` 주입 *전*이라 `tools`가 `null`이다. 반드시 생성자 본문에서, 주입 *뒤*에 만든다.
- **제네릭 업캐스트.** `ofBlocks`는 `List<ContentBlock>`을 받지만 `new ArrayList<>(results)`는 `ArrayList<ToolResultBlock>`으로 추론된다. 제네릭은 불변이라 `List<ContentBlock> blocks = …`로 타입을 못 박아 업캐스트한다.
- **스레드 안전 큐.** `started`/`finished`가 워커(가상) 스레드에서 불릴 수 있으니 `sink.emit`의 큐가 스레드 안전이어야 한다(`EventStreams`의 `LinkedBlockingQueue`라 안전).

## 빠른 시작

```bash
cd ch13
./gradlew test          # 순서 보존·동시성 상한·위험 도구 단독 검증(JDK 21, API 키 불필요)
```
