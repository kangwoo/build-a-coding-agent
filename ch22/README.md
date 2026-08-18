# 22장 코드 — 스킬 시스템: 마크다운으로 정의하는 재사용 능력

책 22장의 산출물. `SKILL.md`(YAML frontmatter + 본문)로 정의한 능력을 발견해, 모델이 `SkillTool`로 호출하면 본문이 현재 대화로 확장된다. 핵심은 **발견과 실행의 분리** — 평소엔 이름+설명만 보이고, 본문(수백 줄)은 호출 시점에야 로드한다. 21장 `PromptCommand` 위에 얹고, frontmatter 파싱용으로 SnakeYAML 의존성이 들어온다.

```
ch22/                                          # ch21 위에 누적
├── gradle/libs.versions.toml                  # ※ + snakeyaml 2.3 — frontmatter(YAML) 파싱
├── agent-core/build.gradle.kts                # ※ + implementation(snakeyaml)
├── agent-core/src/main/java/agent/
│   ├── skill/                                 # ★ 새 패키지 — 스킬 발견·등록·도구화
│   │   ├── SkillCommand.java                  # ★ PromptCommand — 본문 lazy·인자/${AGENT_SKILL_DIR} 치환·MCP 가드
│   │   ├── SkillLoader.java                   # ★ SKILL.md frontmatter(YAML) 파싱 — 깨진 스킬은 건너뜀
│   │   ├── SkillRegistry.java                 # ★ 이름→스킬 등록 + 예산 내 발견 목록(listing)
│   │   └── SkillTool.java                     # ★ 모델이 호출 → 본문 인라인 확장(발견-실행 분리)
│   └── engine/AgentEngine.java                # ※ + injectSystemReminder(스킬 목록 첫 턴 주입)
└── agent-cli/src/main/java/agent/cli/
    └── repl/Repl.java                         # ※ 스킬 발견·SkillTool 등록·목록 주입(컨텍스트 1%) 조립

  agent-core/src/test/java/agent/skill/
    └── SkillTest.java                         # ★ 발견/실행 분리·인자 치환·알 수 없는 스킬 검증

  ★ 신규  ·  ※ 22장에서 변경
```

## 발견과 실행의 분리

- **로딩(`SkillLoader`)**: `.agent/skills/<이름>/SKILL.md`를 훑어 frontmatter(YAML)를 SnakeYAML로 파싱한다. CRLF는 LF로 정규화하고, 닫는 `---`가 없거나 매핑이 아니면 *그 스킬만* 건너뛴다(전체 로딩은 계속). 본문은 캡처만 해 두고 호출 때 펼친다.
- **발견(`SkillRegistry.listing`)**: 매 턴 모델에게는 이름+설명만 `<system-reminder>`로 노출한다. 예산(컨텍스트 1%)을 넘기는 항목은 생략하고 설명도 200자에서 자른다.
- **실행(`SkillTool`)**: 모델이 `Skill` 도구를 호출할 때 비로소 본문을 로드하고 `$ARGUMENTS`를 치환해 `tool_result`로 인라인 반환한다 — 모델이 다음 턴에 그 지침을 따른다. 읽기 전용(`isReadOnly`).
- **스킬 = `PromptCommand`(`SkillCommand`)**: 별도 타입이 아니라 21장 인터페이스 구현. 치환 순서는 인자 → `${AGENT_SKILL_DIR}`이며, 신뢰할 수 없는 MCP 스킬에는 경로 변수 치환을 막는다(`fromMcp` 가드).

## 엔진·REPL 연결

- **`AgentEngine.injectSystemReminder`**: 스킬 목록 같은 메타 텍스트를 `injected=true` user 메시지로 1회 주입하고 영속화한다(18장 `ProjectContext` 주입과 같은 모양).
- **`Repl`**: 부팅 때 스킬을 발견해 `SkillTool`을 도구 레지스트리에 등록하고(엔진 조립 뒤 채워지는 `commandContext`는 지연 참조 위임체로 넘김), 스킬이 있으면 목록을 첫 턴 system-reminder로 주입한다. `/clear` 뒤에도 같은 주입을 반복한다 — 세션 회전(21장) 뒤라 새 세션 파일에 기록된다.

## 빠른 시작

```bash
cd ch22
./gradlew test          # 발견/실행 분리·인자 치환·검증 (JDK 21, API 키 불필요)
```
