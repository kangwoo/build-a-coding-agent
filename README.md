# 밑바닥부터 만드는 코딩 에이전트 — 예제 코드

책 **《밑바닥부터 만드는 코딩 에이전트 — 자바 21로 구현하는 LLM 에이전트의 내부》**의 챕터별 예제 코드 저장소입니다.

각 챕터의 **실제로 컴파일·실행되는** 산출물을 챕터별 디렉터리로 두었습니다. `chXX/`는 그 장을 끝냈을 때의 프로젝트 스냅샷이며, 그 자체로 독립 실행 가능한 Gradle 프로젝트입니다.

## 빠른 시작

JDK 21만 있으면 됩니다(Gradle은 wrapper가 포함돼 있어 따로 설치할 필요가 없습니다).

```bash
git clone https://github.com/kangwoo/build-a-coding-agent.git
cd build-a-coding-agent/ch02

./gradlew test          # 키 불필요 — 동작 검증
export OPENAI_API_KEY="sk-..."
./gradlew run --args="자바의 record를 한 줄로 설명해줘"
```

## 마일스톤

| 시점 | 동작하는 것 |
|---|---|
| 6장 | OpenAI에 붙어 스트리밍 응답이 한 글자씩 출력되는 채팅 |
| 12장 | 파일을 읽고 검색하는 미니 에이전트(한 턴 자동 루프) |
| 19장 | 권한·컨텍스트 관리·자동 압축이 붙은 실용 에이전트 |
| 24장 | OpenAI·Anthropic·Google 멀티 provider 완성형 |

## 챕터별 산출물

```
├── ch02/        # 2장 — OpenAI로 첫 응답 받기 (워밍업 클라이언트 + 키 없는 테스트)
├── ch03/        # 3장 — JLine 대화형 셸(REPL) + 멀티턴(비스트리밍) 대화
├── ch04/        # 4장 — 멀티모듈 골격(agent-core+agent-cli) + 메시지 도메인(Message/ContentBlock/Usage)
├── ch05/        # 5장 — provider-중립 LlmClient SPI + OpenAI 구현(비스트리밍 create)
├── ch06/        # 6장 — 스트리밍(SSE) + 가상 스레드 EventStream + StreamEvent 7종 + 누산기(마일스톤)
├── ch07/        # 7장 — Tool 계약(인터페이스+안전한 기본값) + 레지스트리 + 단일 실행 파이프라인 + EchoTool
├── ch08/        # 8장 — record→JSON Schema 자동 생성 + 2단계 검증(구조·타입 / 의미)
├── ch09/        # 9장 — ReadTool(라인번호·범위·바이트한도·dedup) + FileStateCache(LRU) + ReadResult(멀티모달)
├── ch10/        # 10장 — WriteTool/EditTool + Read-before-Write·staleness(mtime+내용) 안전 모델 + diff(Hunk)
├── ch11/        # 11장 — RipGrep 외부 프로세스 래퍼 + Glob/Grep + WebFetch/SearchBackend(외부 호출 패턴)
├── ch12/        # 12장 — 에이전트 루프(AgentEngine/runLoop) + Transition/AgentEvent/AgentServices + BuiltinTools + REPL 연결(미니 에이전트)
├── ch13/        # 13장 — ToolOrchestrator(동시 실행 안전 도구 병렬·위험 도구 직렬, 인덱스 기반 순서 보존, progress 별도 채널)
├── ch14/        # 14장 — 계층적 CancellationToken(부모-자식 전파·reason·onCancel) + 오케스트레이터 취소 단락 + REPL Ctrl+C 연결
├── ch15/        # 15장 — BashTool(merged fd·타임아웃·tree-kill·종료코드 해석) + onCancel 연결 + BuiltinTools 등록
├── ch16/        # 16장 — 권한 시스템(PermissionGate/RuleBasedGate, 결정 순서=보안) + AgentServices 첫 필드(gate) + JLine 프롬프트
├── ch17/        # 17장 — 훅 시스템(HookEvent/HookCommand/HookRunner, exit2=차단·병렬·워크스페이스 신뢰) + 오케스트레이터 PreToolUse 결합 + HookConfigLoader(settings.json 로더)·WorkspaceTrust(신뢰 장부) REPL 배선
├── ch18/        # 18장 — SystemPromptBuilder(정적/동적 경계)·EnvInfo·ProjectContext(AGENT.md→system-reminder) + 엔진 컨텍스트 주입
├── ch19/        # 19장 — TokenEstimator·ContextManager(마이크로/풀 압축·재귀가드·서킷브레이커)·CostTracker(BigDecimal) + AgentServices 압축·비용(실용 에이전트)
├── ch20/        # 20장 — SessionContext(원자적 정체성·NFC 정규화)·TranscriptStore(append-only JSONL·가상 스레드 디바운스·dedup·복원) + AgentServices 영속성·AgentEngine.resume·--resume
├── ch21/        # 21장 — 슬래시 커맨드(Command sealed: Direct/Prompt, DirectResult, SlashCommands.parse로 명령/경로 구분, CommandRegistry 내장 /help·/cost·/clear, Arguments.substitute) + REPL 입력 라우팅
├── ch22/        # 22장 — 스킬 시스템(SKILL.md frontmatter 로더+SnakeYAML, SkillCommand=PromptCommand 본문 lazy 로드·인자/${AGENT_SKILL_DIR} 치환·fromMcp 가드, SkillRegistry 1% 예산 listing, 모델이 호출하는 SkillTool) + REPL 등록·첫 턴 system-reminder 주입
├── ch23/        # 23장 — 서브에이전트 위임(AgentDefinition·SubagentRunner/EngineSubagentRunner=새 AgentEngine 격리 실행·AssistantTextDelta만 누적, SubagentTools.poolFor=허용 도구 필터+Agent 제외 재귀방지, 동기 AgentTool·Task 생명주기 모델+비동기 골격) + REPL 와이어링
├── ch24/        # 24장 — 멀티 provider 완성(AnthropicClient/AnthropicWire/AnthropicConfig=system top-level·tool_use/tool_result content 블록·cache_control 마커 1개·thinking budget temperature 미전송·input_schema, GeminiClient/GeminiWire/GeminiConfig=contents/systemInstruction·functionCall/Response·?key= URL·message_start 합성, LlmClients.forProvider에 anthropic/gemini 채움) — LlmClient 추상화 무수정 검증(완성형 마일스톤)
├── ch25/        # 25장 — MCP 연동(StdioMcpClient=JSON-RPC 2.0 over stdio·가상 스레드 리더 루프+id 상관 맵·initialize 핸드셰이크·tree-kill close, McpTool=MCP 도구를 우리 Tool로 감싸는 어댑터·이름 mcp__<서버>__<도구> 정규화·content[]→ContentBlock·inputSchema 생략 시 허용형 빈 객체 스키마, McpTools.loadMcpTools 수동 훅) — 코어 무수정·추가만 하는 선택적 확장(마지막 조각)
└── appendix-c5/  # 부록 C.5 — 비동기 서브에이전트 완성(ch25 사본 + 추가): BackgroundTasks(디스크 append 출력·바이트 오프셋 증분 읽기·분리된 취소·완료 우선 전이) + TaskOutput/TaskStop 도구. 23장 골격의 참조 구현(곁가지, 누적 아님)
```

## 공통 규약

- 루트 프로젝트명은 `coding-agent`, 패키지 루트는 `agent.*`입니다(책 본문과 동일).
- 각 `chXX/`에는 Gradle wrapper가 포함돼 별도 설치 없이 `./gradlew`로 빌드됩니다(JDK 21만 필요).
- 테스트는 **API 키 없이** 통과합니다. 실제 LLM 호출은 `./gradlew run`으로 키가 있을 때만 일어납니다.
- 빌드 산출물(`.gradle/`, `build/`)은 각 디렉터리의 `.gitignore`로 제외됩니다.
- 일부 테스트는 외부 도구(예: `ripgrep`)에 의존하며, 없으면 깔끔하게 건너뜁니다.

> 챕터가 진행되며 코드는 멀티모듈(`agent-core` + `agent-cli`)로 자랍니다(정식 골격은 4장·부록 A). 이후 장의 `chXX/`는 직전 장의 산출물 위에 누적됩니다. 즉 ch11은 ch10에 그 장의 추가분을 얹은 것입니다.

## 자주 겪는 문제

- **`JAVA_TOOL_OPTIONS`가 테스트 워커에 상속되는 경우** — 셸에서 이 변수를 export해 두었다면(예: `-Xmx4g`) Gradle이 포크한 테스트 JVM이 이를 물려받아 테스트 시작 전에 죽을 수 있습니다. `env -u JAVA_TOOL_OPTIONS ./gradlew build`로 실행하세요.
- **이름이 바뀐 클래스가 오류에 등장하는 경우** — `build/`에 남은 예전 컴파일 산출물 때문입니다. `./gradlew clean build`를 실행하세요.
