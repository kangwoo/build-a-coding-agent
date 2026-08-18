# 23장 코드 — 서브에이전트와 Task: 격리된 위임

책 23장의 산출물. 메인 에이전트가 하위 작업을 격리된 서브에이전트에 위임하는 **`AgentTool`**(와이어 이름 `Agent`)을 만든다. 핵심은 12장 `AgentEngine`을 **재귀적으로 재사용**한다는 점이다 — 서브에이전트는 새 엔진(=새 메시지 배열)으로 돌고, 부모는 그 *결과(요약)만* `tool_result`로 받아 컨텍스트를 아낀다.

```
ch23/                                       # ch22 위에 누적
├── agent-core/src/main/java/agent/
│   └── subagent/                           # ★ 새 패키지 — 서브에이전트 위임(Task 도구)
│       ├── AgentDefinition.java            # ★ 서브에이전트 종류: 이름·시스템 프롬프트·허용 도구 집합
│       ├── SubagentRunner.java             # ★ 격리 실행 인터페이스 run(def, prompt, cancel) → 요약 텍스트
│       ├── EngineSubagentRunner.java       # ★ 12장 AgentEngine 재사용 — 새 엔진=새 메시지 배열, 최종 텍스트만 수집(인터럽트 시 부분 결과 보존)
│       ├── SubagentTools.java              # ★ poolFor: allowedTools만 남김 + Agent 제외(무한 재귀 방지)
│       ├── AgentTool.java                  # ★ Task 도구 — 동기 위임, 결과(요약)만 tool_result로 부모에 반환
│       └── Task.java                       # ★ 비동기 생명주기 모델(State/Status) — 골격
└── agent-cli/src/main/java/agent/cli/repl/
    └── Repl.java                           # ※ 메인 풀에 AgentTool 등록(서브 풀 poolFor는 Agent 제외)

  agent-core/src/test/java/agent/subagent/
    ├── AgentToolTest.java                  # ★ 가짜 러너로 위임→요약 반환 검증
    ├── EngineSubagentRunnerTest.java       # ★ 가짜 모델로 격리·최종 텍스트만 수집·인터럽트 부분 보존 검증
    ├── SubagentToolsTest.java              # ★ 허용 도구만 남김·Agent 절대 제외·미지 이름 무시
    └── TaskTest.java                       # ★ 생명주기 전이·터미널 판정(불변 State)

  ★ 신규  ·  ※ 23장에서 변경
```

## 위임의 핵심

- **엔진 재사용으로 격리 실행**: `EngineSubagentRunner`는 `run()`마다 새 `AgentEngine`을 세운다 — 새 엔진은 새 메시지 배열이라 부모 컨텍스트가 새지 않는다. 루프를 끝까지 돌리되 `AssistantTextDelta`만 모아 `strip`한 **최종 텍스트만** 반환한다(도구를 부른 턴의 진행 텍스트는 도구 호출을 보는 순간 비우고, 도구 이벤트는 부모에 안 흘린다). 소비 스레드가 인터럽트되면(`StreamCancelledException`, 6장) 모은 부분 텍스트를 "[중단됨 …]" 표시와 함께 보존해 돌려준다 — 이미 지불한 토큰을 버리지 않는다.
- **서브에이전트 정의**: `AgentDefinition`은 종류 하나 = 이름 + 시스템 프롬프트 + `allowedTools`(이름 집합). `GENERAL`은 읽기·탐색(`Read`/`Glob`/`Grep`)만 허용한다 — 위임 작업엔 보통 그걸로 충분하다.
- **도구 화이트리스트 + 재귀 방지**: `SubagentTools.poolFor`가 내장 레지스트리를 순회해 `allowedTools`에 든 이름만 골라 담고, **`Agent`(=`AgentTool`)는 무조건 제외**한다. 서브가 또 서브를 띄우는 무한 재귀를 막는 불변식이다. 스냅샷에 없는 이름(15장 전의 `Bash` 등)은 자연히 무시된다.
- **동기 위임**: `AgentTool.call`은 결과가 올 때까지 블로킹하고, `ctx.cancel()`로 부모 취소가 전파된다. 비동기는 `Task`(상태 전이 모델)로 골격만 둔다 — 분리된 취소·백그라운드 실행은 개념으로 짚는다.

## REPL 와이어링

- `Repl.run()`에서 `EngineSubagentRunner`(러너)와 `AgentTool`을 엮어 **메인 도구 레지스트리에만** 등록한다. 서브 풀은 `SubagentTools::poolFor`로 만들어지고 `Agent`를 빼므로, 위임은 한 단계로 끝난다.

## 빠른 시작

```bash
cd ch23
./gradlew test          # 가짜 모델·러너로 위임/격리/재귀 방지 검증(JDK 21, API 키 불필요)
```
