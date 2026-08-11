# 14장 코드 — 취소와 인터럽트: 계층적 CancellationToken

[`chapters/14-취소와-인터럽트.md`](../../chapters/14-취소와-인터럽트.md)의 산출물. 5장에서 뼈대만 둔 `CancellationToken`을 **부모-자식 트리**(reason·정리 콜백)로 키우고, JLine의 Ctrl+C와 연결한다. 핵심은 멈춰도 불변식을 지키는 것 — 취소된 도구에도 합성 `tool_result`를 채워 다음 모델 호출이 깨지지 않게 한다.

```
ch14/                                    # ch13 위에 누적
├── agent-core/src/main/java/agent/
│   ├── exec/
│   │   ├── CancellationToken.java       # ※ 부모-자식 트리·reason·onCancel 정리 콜백
│   │   └── ToolOrchestrator.java        # ※ runOne에 취소 단락+도구별 자식 토큰
│   └── tool/
│       ├── ToolContext.java             # ※ + withCancel(토큰만 바꾼 사본)
│       └── ToolExecutor.java            # ※ + 취소 분기(CancellationException → '취소됨', 오류 아님)
└── agent-cli/src/main/java/agent/cli/
    └── repl/Repl.java                   # ※ converse가 Terminal 받아 Ctrl+C(SIGINT)→interrupt 취소
                                         #    + 취소·재진입 예외 방어 catch(프로세스 생존)

  agent-core/src/test/java/agent/exec/
    ├── CancellationTokenTest.java       # ★ 부모→자식 전파·onCancel·멱등성
    └── ToolOrchestratorCancelTest.java  # ★ 취소 컨텍스트→모든 결과 합성 오류, 도구 미실행
  agent-core/src/test/java/agent/tool/
    └── ToolExecutorTest.java            # ※ + 취소 분기 테스트('취소됨' ≠ 오류)

  ★ 신규  ·  ※ 14장에서 변경
```

## 취소는 트리다 (cooperative cancellation)

- **부모→자식 전파**: 루트 토큰이 취소되면 모든 자식(도구별) 토큰이 따라 취소된다. 자식만 취소하면 형제는 멀쩡하다. 오케스트레이터는 도구마다 `cancel().child()`를 준다.
- **협조적 취소**: 토큰은 스레드를 강제로 죽이지 않는다. SSE 루프·도구가 `isCancelled()`를 스스로 확인해 빠져나오고, `onCancel`로 등록한 정리 콜백(프로세스 kill 등은 15장)이 취소 시 돈다. 리스너 예외는 삼켜 다른 정리를 막지 않는다.
- **reason으로 종류 구분**: `interrupt`(Ctrl+C·부분 결과 보존)와 일반 `abort`(강제 종료)를 boolean이 아니라 reason으로 나눈다 — `isInterrupt()`로 분기한다. `cancel`은 멱등이라 Ctrl+C를 두 번 눌러도 첫 이유가 유지된다.

## 멈춰도 불변식은 지킨다

- **Ctrl+C 연결**: REPL이 턴 동안만 JLine `SIGINT`를 `interrupt` 취소로 잇고, `finally`에서 이전 핸들러로 원복한다. 신호 연결·해제가 `converse` 한 곳에 모인다.
- **취소 단락(합성 결과)**: 취소된 컨텍스트로 들어온 도구는 본문을 실행하지 않고 "취소됨" 오류 결과를 만든다. 그래서 아직 시작 못 한 `tool_use`에도 짝 `tool_result`가 채워져, 다음 호출이 400 오류로 깨지지 않는다. 도구 *안에서* 취소가 예외로 올라와도(`CancellationException`) `ToolExecutor`가 같은 '취소됨' 표기로 가둔다 — "도구 실행 오류"가 아니다.
- **REPL 방어 catch**: 스트림 소비의 `StreamCancelledException`(6장)과 `submit()` 재진입 가드의 `IllegalStateException`(12장)을 `converse`가 잡아 알림 한 줄로 바꾼다. 구체 타입을 먼저 잡아야 취소가 계약 위반으로 새지 않는다(전자가 후자의 하위 타입).
- **Aborted 전이**: 엔진 루프가 맨 위에서 취소를 보면 `Transition.Aborted("interrupt")`로 종료하고, REPL이 "취소됨: …" 한 줄로 드러낸다.

## 빠른 시작

```bash
cd ch14
./gradlew test          # 토큰 트리·정리 콜백·멱등성·취소 단락/표기 검증(JDK 21, API 키 불필요)
```
