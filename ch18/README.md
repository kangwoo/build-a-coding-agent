# 18장 코드 — 시스템 프롬프트 조립과 컨텍스트 주입

[`chapters/18-시스템-프롬프트-조립.md`](../../chapters/18-시스템-프롬프트-조립.md)의 산출물. 시스템 프롬프트를 한 줄로 때우던 것을 **섹션(`PromptSection`) 조립**으로 바꾼다. 정체성·행동 규칙·도구 사용법(정적)을 앞에, 환경 정보(`EnvInfo`: cwd·git·OS·모델)를 동적 경계 뒤에 두어 OpenAI 자동 프리픽스 캐싱이 적중할 순서를 지킨다. 경계는 `SystemBlock` 리스트로 `LlmRequest.system`까지 실려 가(5장 약속 이행) 24장 Anthropic이 마지막 정적 블록에 캐시 마커를 찍는 근거가 된다. 자주 바뀌는 `AGENT.md`·날짜는 시스템 프롬프트가 아니라 첫 `<system-reminder>` user 메시지(`ProjectContext`)로 주입해 프리픽스 안정성을 지킨다.

```
ch18/                                  # ch17 위에 누적
├── agent-core/src/main/java/agent/
│   ├── context/                       # ★ 새 패키지 — 시스템 프롬프트 조립
│   │   ├── PromptSection.java         # ★ 한 섹션(이름·compute·cacheBreak), static/dynamic 팩토리
│   │   ├── EnvInfo.java               # ★ cwd·git·OS·모델 → <env> 블록 렌더
│   │   ├── ProjectContext.java        # ★ AGENT.md+날짜 → <system-reminder> 주입 user 메시지
│   │   └── SystemPromptBuilder.java   # ★ 정체성·규칙·도구→경계→env 조립(build/blocks/render)
│   ├── llm/
│   │   ├── SystemBlock.java           # ★ provider 중립 system 블록(text·dynamic)
│   │   ├── LlmRequest.java            # ※ system: String → List<SystemBlock>(5장 약속 이행)
│   │   └── openai/OpenAiClient.java   # ※ systemText()로 이어 붙여 전송(wire 바이트 동일)
│   └── engine/AgentEngine.java        # ※ ProjectContext 1회 주입·주 생성자 List<SystemBlock>
└── agent-cli/src/main/java/agent/cli/
    └── repl/Repl.java                 # ※ blocks()로 조립한 블록 리스트를 엔진에 주입

  agent-core/src/test/java/agent/context/
  └── SystemPromptTest.java            # ★ 섹션 순서·경계 블록·env(cwd/git)·AGENT.md 주입 검증

  ★ 신규  ·  ※ 18장에서 변경
```

## 섹션 조립과 캐시 순서

- **정적 → 경계 → 동적**: `SystemPromptBuilder`가 정체성·행동 규칙·도구 사용법(정적)을 앞에, `EnvInfo`(`<env>`: cwd·git·OS·모델)를 `DYNAMIC_BOUNDARY` 뒤에 둔다. 정적이 앞에 모여야 OpenAI 자동 프리픽스 캐싱이 적중한다.
- **세 출력**: `build()`는 경계 마커를 *포함한* 섹션 배열을 주고, `blocks()`는 그 경계에서 갈라 `SystemBlock` 리스트로 나른다(`LlmRequest.system`에 실려 24장 Anthropic이 마지막 정적 블록에 `cache_control`을 찍음). `render()`는 마커를 빼 한 문자열로 합친다(한 문자열이 필요한 자리·테스트용 — provider 조인은 `LlmRequest.systemText()`). **빈 섹션은 거른다** — 빈 줄이 끼면 프리픽스가 흔들려 캐시가 빗나간다.

## 두 컨텍스트 주입은 자리가 다르다

- **환경 정보**는 시스템 프롬프트 *끝*에, **`AGENT.md`·날짜**는 대화 *첫 user 메시지*로 넣는다(`<system-reminder>`, `injected=true`). 자주 바뀌는 건 메시지 쪽으로 빼 시스템 프롬프트 프리픽스의 캐시 안정성을 지킨다.
- **연결 지점**: 프롬프트 조립은 REPL이 `blocks()`로 맡고, `AgentEngine`은 주 생성자가 `List<SystemBlock>`을 받되 기존 String 생성자를 정적 한 블록으로 감싸는 편의 오버로드로 남긴다 — 테스트·서브에이전트가 안 깨진다. 엔진은 `submit` 첫 호출에서 `ProjectContext`를 한 번만 주입한다(`contextInjected`).

## 빠른 시작

```bash
cd ch18
./gradlew test          # 섹션 순서·env·AGENT.md 주입 검증(JDK 21, API 키 불필요)
```
