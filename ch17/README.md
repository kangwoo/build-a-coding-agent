# 17장 코드 — 훅 시스템: 생명주기 확장점

[`chapters/17-훅-시스템.md`](../../chapters/17-훅-시스템.md)의 산출물. 16장 권한이 *내장된* 안전장치라면, 훅은 사용자가 설정만으로 꽂는 확장점이다. 도구 실행 전/후 같은 생명주기 지점에서 `.agent/settings.json`에 등록한 외부 명령을 돌려, 코드 수정 없이 "Write 전에 린터를 돌려라" 같은 자동화를 붙인다. PreToolUse 훅은 exit 2로 도구 실행을 차단하거나 추가 컨텍스트를 모델에 주입한다.

```
ch17/                                          # ch16 위에 누적
├── agent-core/src/main/java/agent/
│   ├── hook/                                  # ★ 새 패키지 — 이벤트+매처+명령 조합
│   │   ├── HookEvent.java                     # ★ 생명주기 지점 enum(PreToolUse/PostToolUse/Stop/…)
│   │   ├── HookMatcher.java                   # ★ 매처 3중 매칭: 빈값·* / Write|Edit / 정규식
│   │   ├── HookCommand.java                   # ★ sealed 훅 정의 — 지금은 command형(+timeout)
│   │   ├── HookResult.java                    # ★ 결과 3종: SUCCESS/BLOCKING/NON_BLOCKING_ERROR
│   │   ├── CommandHookExecutor.java           # ★ 외부 명령 실행 — stdin JSON+개행, exit 0/2/그외
│   │   └── HookRunner.java                    # ★ 매칭+가상스레드 병렬+집계(deny>allow)+신뢰 경계
│   ├── engine/
│   │   ├── AgentServices.java                 # ※ + HookRunner hooks 필드(12장 이음매의 둘째 칸)
│   │   └── AgentEngine.java                   # ※ orchestrator 생성 시 services.hooks() 주입
│   └── exec/ToolOrchestrator.java             # ※ runOne에 PreToolUse 훅 결합(deny 차단·컨텍스트 덧붙임)
└── agent-cli/src/main/java/agent/cli/repl/
    └── Repl.java                              # ※ 조립부에 HookRunner.none() 한 줄 추가

  agent-core/src/test/java/agent/hook/
    ├── CommandHookExecutorTest.java           # ★ 타임아웃 강제 종료·stderr 우선·JSON+exit 2 조합
    └── HookRunnerTest.java                    # ★ exit 2 차단·미신뢰 워크스페이스 건너뛰기

  ★ 신규  ·  ※ 17장에서 변경
```

## 훅 프로토콜과 결정

- **이벤트 + 매처 + 명령**: `HookEvent`(PreToolUse/PostToolUse/UserPromptSubmit/Stop/SessionStart·End) × `HookMatcher`(도구 이름 매칭) × `HookCommand`(실행할 명령)의 조합이다. 핵심은 PreToolUse — 도구 실행 직전에 허용·거부·컨텍스트 주입을 결정한다.
- **stdin/stdout/종료코드로 말한다**: `CommandHookExecutor`가 `{tool_name, tool_input, …}` JSON을 stdin으로 넘기고(**끝에 개행 필수** — `bash`의 `read -r`이 개행 없으면 못 읽는다), 종료코드로 결과를 읽는다. **exit 0=통과, 2=차단(blocking), 그 외=비차단 오류**. 셋을 뭉개면 의도된 차단과 망가진 스크립트를 구별 못 한다.
- **JSON 응답(선택)**: stdout이 `{`로 시작하면 `permissionDecision`·`message`·`additionalContext` 셋만 읽는다.
- **타임아웃**: 기본 60초, 넘기면 `destroyForcibly` 후 비차단 오류로 처리해 차단으로 오해하지 않게 한다.
- **매처 3중 매칭**: 빈값·`*`=전체, `Write|Edit`=정확/파이프, 그 외=정규식. `HookResult.Outcome`은 SUCCESS/BLOCKING/NON_BLOCKING_ERROR 셋이다.

## 신뢰 경계와 권한 결합

- **신뢰 경계**: 훅은 아무 셸 명령이나 실행하므로 신뢰하지 않은 워크스페이스(남의 저장소)에선 RCE다. `HookRunner`는 실행 전 `trusted`를 확인하고, 신뢰하지 않으면 훅을 아예 돌리지 않는다.
- **병렬 실행·집계**: `HookRunner`가 매칭된 훅을 가상 스레드로 병렬 실행하고 결과를 모은다 — 하나라도 차단/deny면 거부한다(**deny > allow** 2단계, ask는 16장 권한 시스템에 위임).
- **allow가 권한을 우회 못 함**: `ToolOrchestrator.runOne`은 훅 deny면 즉시 차단하지만, 훅이 통과해도 16장 권한 게이트는 그대로 적용한다. 훅이 더한 `additionalContext`는 `tool_result` 뒤에 붙여 모델이 보게 한다.
- **12장 이음매**: `AgentServices`에 `HookRunner hooks` 한 필드를 더하고 `defaults()`를 `HookRunner.none()`으로, REPL 조립부 한 줄만 고치면 된다 — `AgentEngine` 생성자 시그니처는 그대로다.

## 빠른 시작

```bash
cd ch17
./gradlew test          # exit 2 차단·미신뢰 건너뛰기 검증(JDK 21, API 키 불필요; bash 필요·Windows 스킵)
```
