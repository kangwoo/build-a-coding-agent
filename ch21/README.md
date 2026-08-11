# 21장 코드 — 슬래시 커맨드: 다이렉트/프롬프트 분기와 REPL 라우팅

[`chapters/21-슬래시-커맨드.md`](../../chapters/21-슬래시-커맨드.md)의 산출물. `/help`·`/cost`·`/clear`처럼 모델을 거치지 않고 에이전트를 직접 조작하는 **슬래시 커맨드** 시스템이다. `Command`를 sealed로 두 갈래 — 즉시 실행하는 `DirectCommand`와 텍스트로 확장돼 모델에 주입되는 `PromptCommand` — 로 나눠 컴파일러가 분기를 빠짐없이 챙기게 한다. REPL 입력에서 `/`로 시작하는 줄을 명령으로 라우팅하며, `PromptCommand` 경로가 22장 스킬의 토대다.

```
ch21/                                         # ch20 위에 누적
├── agent-core/src/main/java/agent/command/   # ★ 새 패키지 — 슬래시 커맨드 시스템
│   ├── Command.java                          # ★ sealed: DirectCommand | PromptCommand
│   ├── DirectResult.java                     # ★ sealed: Text | Skip | ClearHistory | Exit
│   ├── CommandContext.java                   # ★ 명령→에이전트 상태 통로(비용·대화·목록)
│   ├── SlashCommands.java                    # ★ parse — 명령 vs 파일 경로 구분
│   ├── CommandRegistry.java                  # ★ 등록·조회 + 내장 /help·/cost·/clear·/exit·/quit
│   └── Arguments.java                        # ★ $ARGUMENTS·$1 치환(프롬프트·스킬 공용)
└── agent-cli/src/main/java/agent/cli/repl/
    └── Repl.java                             # ※ 입력에서 슬래시 커맨드 라우팅

  agent-core/src/test/java/agent/command/
    └── SlashCommandTest.java                 # ★ 파싱·도움말·치환·경로 구분

  ★ 신규  ·  ※ 21장에서 변경
```

## 슬래시 커맨드의 두 갈래

- **`Command` sealed**: `DirectCommand`는 즉시 실행하고 끝(모델 비경유), `PromptCommand`는 텍스트로 확장돼 모델에 주입된다. 둘을 합치지 않아 "비용 표시"가 괜히 LLM을 호출하지 않는다.
- **`DirectResult` sealed**(Text·Skip·ClearHistory·Exit): 다이렉트 명령의 부수효과 + 화면 출력 결과. `/clear`는 엔진의 `clearConversation()`으로 대화를 초기화하고 세션을 회전(새 sessionId — 20장 `switchSession`)한 뒤 `ClearHistory`를 돌려준다. 회전 없이 같은 파일에 계속 기록하면 `--resume`이 "지운" 대화까지 복원한다.
- **파싱**: `SlashCommands.parse`가 명령과 파일 경로를 가른다 — 이름은 슬래시 직후부터 첫 공백 전까지의 토큰이고 `[a-zA-Z0-9:_-]`만 허용한다. `/var/log`·`/tmp/x.txt`는 명령이 아니다.
- **레지스트리**: `CommandRegistry.withBuiltins()`가 `/help`·`/cost`·`/clear`·`/exit`·`/quit`을 등록한다(LinkedHashMap으로 등록 순서 유지). 명령은 `CommandContext`를 통해 비용·대화·명령 목록에 닿는다. 종료도 명령이라 `/help` 목록에 나온다 — `Exit`는 신호일 뿐, 실제 종료는 임베더(REPL) 몫이다.
- **인자 치환**: `Arguments.substitute`가 `$ARGUMENTS`·`$1`을 채운다(프롬프트 커맨드·스킬 공용). `quoteReplacement`로 `$`·`\`를 리터럴로 지키고, 정규식 경계로 `$10`이 `$1`로 부분 치환되는 함정을 피한다.

## REPL 라우팅

- 입력 한 줄은 `handleInput`이 분기한다: 슬래시 명령이면 `runCommand`, 아니면 `converse`(에이전트 루프, 12장). `/exit`·`/quit`도 이 라우팅을 탄다 — `Exit` 결과가 `handleInput`의 `true`로 번역되고, 루프 탈출은 루프 자신이 한다.
- `runCommand`는 다이렉트면 즉시 끝내고, 프롬프트면 `blocksToText`로 펼쳐 한 턴 `converse`한다(스킬도 이 경로 — 22장). `Command`와 `DirectResult` 두 `switch`가 모두 sealed라 컴파일러가 분기를 빠짐없이 검사한다.

## 빠른 시작

```bash
cd ch21
./gradlew test          # 파싱·도움말·치환·경로 구분 검증(JDK 21, API 키 불필요)
```
