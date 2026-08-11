# 16장 코드 — 권한 시스템: allow / deny / ask

[`chapters/16-권한-시스템.md`](../../chapters/16-권한-시스템.md)의 산출물. 도구 실행 직전에 **허용/거부/질문(allow/deny/ask)**을 정하는 권한 게이트를 넣어, 15장에서 확인 없이 아무 명령이나 돌리던 Bash의 구멍을 막는다. 12장에서 비워 둔 `AgentServices` 이음매에 **첫 필드(게이트)**가 채워지고, 그 게이트가 엔진→오케스트레이터→`ToolExecutor`로 한 줄씩 흘러 도구 실행 ④단계에 꽂힌다. 핵심은 켜고 끄기가 아니라 **결정의 순서 자체가 보안**이라는 점이다.

```
ch16/                                          # ch15 위에 누적
├── agent-core/src/main/java/agent/
│   ├── permission/                            # ★ 새 패키지 — 권한 모델·게이트
│   │   ├── PermissionMode.java                # ★ DEFAULT/ACCEPT_EDITS/BYPASS/PLAN
│   │   ├── PermissionRule.java                # ★ "Bash(npm install)" 파싱·접두사 매칭
│   │   ├── PermissionContext.java             # ★ 모드+규칙 묶음, 항상 허용 시 규칙 추가(onChange)
│   │   ├── PermissionPrompt.java              # ★ 대화형 ask 인터페이스(코어는 방법 모름)·denying()
│   │   ├── PermissionGate.java                # ★ 게이트 SPI + allowAll()(도구 Deny는 면역)
│   │   └── RuleBasedGate.java                 # ★ 결정 순서=보안: deny→ask규칙→도구검사→bypass→allow→기본값
│   ├── engine/
│   │   ├── AgentServices.java                 # ※ + PermissionGate gate(12장 이음매의 첫 필드)
│   │   └── AgentEngine.java                   # ※ 오케스트레이터를 services.gate()로 조립
│   ├── exec/ToolOrchestrator.java             # ※ + gate 필드, runToolUse에 게이트 전달
│   └── tool/
│       ├── Tool.java                          # ※ + permissionSubject(규칙 매칭용 문자열)
│       ├── ToolExecutor.java                  # ※ ④ 권한 단계를 도구검사→게이트로(3-arg 오버로드 유지)
│       └── builtin/
│           ├── BashTool.java                  # ※ + permissionSubject=명령 문자열
│           ├── WriteTool.java                 # ※ + permissionSubject=파일 경로
│           └── EditTool.java                  # ※ + permissionSubject=파일 경로
└── agent-cli/src/main/java/agent/cli/
    ├── permission/JLinePermissionPrompt.java  # ★ "[a]llow once/[A]lways/[d]eny" 읽어 Answer로(EOF=거부)
    └── repl/Repl.java                         # ※ RuleBasedGate+JLine 프롬프트 조립 → AgentServices 주입

  agent-core/src/test/java/agent/
    ├── permission/RuleBasedGateTest.java      # ★ deny>bypass·deny>allow·acceptEdits·읽기전용 통과·항상 허용 규칙 영속
    └── tool/builtin/BashToolTest.java         # ※ 출력 상한 케이스 정리

  ★ 신규  ·  ※ 16장에서 변경
```

## 결정 순서가 곧 보안

- **게이트의 결정은 셋**: `allow`(그냥 실행) · `deny`(거부 사유를 모델에 돌려줌) · `ask`(사용자에게 물음). `RuleBasedGate`가 정한다.
- **순서가 보안이다**: ① deny 규칙 → ② ask 규칙 → ③ 도구 자체 안전 검사 → ④ bypass 모드 → ⑤ allow 규칙 → ⑥ 기본값. 이 순서를 바꾸면 보안이 무너진다.
- **deny·ask·도구 안전 검사는 bypass보다 *앞*이다**(bypass-immune): "전부 허용"이라도 콕 집어 금지한 것, 본래 위험한 것은 못 넘는다. `allowAll()` 게이트조차 도구 자체 `Deny`는 존중한다.
- **기본값은 `isReadOnly`(7장)**: Read·Glob 같은 읽기 전용은 묻지 않고 통과(allow), Write·Bash 같은 부수효과 도구는 기본적으로 묻는다(ask).

## 규칙·도구 메타데이터·프롬프트

- **모드 네 가지**(`PermissionMode`): DEFAULT · ACCEPT_EDITS(편집 도구 자동 허용) · BYPASS · PLAN.
- **규칙은 문자열**(`PermissionRule`): `"Bash(npm install)"`처럼 `ToolName(content)` 형식이고 content는 접두사로 매칭한다. "항상 허용"을 고르면 규칙으로 저장돼 다음부터 안 묻는다 — 이 장은 인메모리, 디스크 영속은 `onChange` 콜백에 꽂는 20장의 몫이다.
- **도구가 매칭 문자열을 노출**(`Tool.permissionSubject` 기본 메서드): Bash는 명령 문자열, Write/Edit는 파일 경로를 돌려준다. 코어 `Tool`에 default 한 줄을 더해 기존 도구를 깨지 않는다.
- **게이트는 실행 직전 ④단계**: `ToolExecutor`가 도구 자체 검사 대신 게이트를 부른다(3-arg 오버로드를 남겨 7·8·13·14장 호출처가 그대로 컴파일된다). 게이트는 `AgentServices`→엔진→`ToolOrchestrator`→`ToolExecutor`로 흐른다.
- **프롬프트 UX는 CLI 몫**(`PermissionPrompt`): 코어는 인터페이스만 알고, JLine `JLinePermissionPrompt`가 "[a]llow once / [A]lways / [d]eny"를 읽는다. 비대화형·헤드리스는 `denying()`으로 ask=deny(fail-closed).

## 빠른 시작

```bash
cd ch16
./gradlew test          # 결정 순서·기본 허용·항상 허용 규칙 영속 검증(JDK 21, API 키 불필요)
```
