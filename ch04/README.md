# 4장 코드 — 프로젝트 골격과 메시지 도메인 모델링

[`chapters/04-프로젝트-골격과-메시지-도메인.md`](../../chapters/04-프로젝트-골격과-메시지-도메인.md)의 산출물. 3장까지 `List<Map<String,String>>`로 들고 다니던 대화를 진짜 **타입**(`Message`/`ContentBlock`/`Usage`)으로 승격하고, 프로젝트를 **멀티모듈**(`agent-core` + `agent-cli`)로 정리한다.

```
ch04/
├── settings.gradle.kts            # include("agent-core", "agent-cli")
├── build.gradle.kts               # 루트 — subprojects { 툴체인·repositories·useJUnitPlatform }
├── gradle/libs.versions.toml      # 버전 카탈로그(4장에서 jackson-jsr310 추가)
├── gradlew, gradle/wrapper/…      # Gradle 8.10 wrapper
├── agent-core/                    # 순수 도메인/엔진 — JLine·picocli에 의존하지 않음
│   ├── build.gradle.kts           # jackson(databind+jsr310) + 테스트(junit5·assertj)
│   └── src/
│       ├── main/java/agent/message/
│       │   ├── ContentBlock.java  # sealed — text/thinking/tool_use/tool_result/image
│       │   ├── Message.java       # sealed — user/assistant/system
│       │   ├── Usage.java         # 토큰 사용량 + 누적(cumulative) 병합 규칙
│       │   └── Json.java          # 공유 ObjectMapper(JavaTimeModule, NON_NULL)
│       └── test/java/agent/message/
│           └── MessageSerializationTest.java    # 라운드트립·누적 불변식(키 불필요)
└── agent-cli/
    ├── build.gradle.kts           # agent-core + jackson(warmup용) + jline + picocli
    └── src/
        ├── main/java/agent/
        │   ├── warmup/            # 2장 누적: OpenAiChat(REPL이 사용), Main(구 진입점)
        │   └── cli/
        │       ├── Main.java                    # picocli 런처(정식 진입점)
        │       ├── bootstrap/{CliApplication,Bootstrap,Session}.java
        │       ├── render/Renderer.java         # message(Message)/plainText(Message)로 승격
        │       └── repl/
        │           ├── Repl.java                # conversation: List<Message>로 승격
        │           └── Wire.java                # Message→wire(Map) 임시 어댑터(5장에서 삭제)
        └── test/java/agent/
            ├── warmup/OpenAiChatTest.java       # 2장 누적
            └── cli/render/RendererTest.java     # plainText/message 검증으로 갱신(키 불필요)
```

## 테스트 (API 키 불필요)

```bash
./gradlew test          # agent-core(4) + agent-cli(5) = 9개 테스트
```

- `agent-core`: 메시지 도메인의 **JSON 라운드트립**(쓰고 다시 읽으면 같다)과 **usage 누적 병합**(0은 '값 없음'으로 무시) 불변식.
- `agent-cli`: `Renderer.plainText`가 text 블록만 이어 붙이는지, 역할별 `message(Message)` 렌더, 그리고 2장 `OpenAiChat`의 로컬 스텁 왕복.

## 대화 실행 (API 키 필요)

```bash
export OPENAI_API_KEY="sk-..."
./gradlew run
# 터미널 코딩 에이전트  ·  /exit 로 종료
# › 자바에서 record가 뭐야? 한 줄로.
# ...
# › /exit
# 안녕히 가세요 👋
```

3장과 달리 대화가 `List<Message>`에 타입으로 누적된다. 호출 실패 시 `NoticeMessage`(노란색)로 알리고 방금 넣은 user 메시지를 되돌려 대화 기억을 오염시키지 않는다.

> `./gradlew run`이 표준 입력을 못 넘기면 배포 스크립트로 직접 실행한다(부록 A.5):
> ```bash
> ./gradlew :agent-cli:installDist
> ./agent-cli/build/install/agent-cli/bin/agent-cli   # 진짜 TTY에서 REPL
> ```
> TTY가 아닌 곳에서는 JLine이 더미 터미널 경고를 찍고 떨어진다 — **정상이다**(색·라인편집만 빠진다).

## 참고

- 기본 모델 `gpt-5.4-mini`는 자리표시자다 — 실제 호출 시 현재 OpenAI 모델 id로 교체(부록 A.4).
- `agent.message.NoticeMessage`는 **내부 알림**용이지 LLM의 system 프롬프트가 아니다(그건 18장).
- `Wire`(Message→Map 어댑터)는 5장에서 `LlmClient`가 `List<Message>`를 직접 먹게 되면 통째로 삭제된다.
