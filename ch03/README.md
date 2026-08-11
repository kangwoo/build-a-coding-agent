# 3장 코드 — JLine 대화형 셸(REPL)과 첫 대화

[`chapters/03-JLine-대화형-셸-골격.md`](../../chapters/03-JLine-대화형-셸-골격.md)의 산출물. 2장의 `OpenAiChat`을 **JLine REPL** 위에 얹어 멀티턴(비스트리밍) 대화를 주고받는다. 정식 진입점이 `agent.cli.Main`으로 옮겨졌다.

```
ch03/
├── settings.gradle.kts            # include("agent-cli")
├── build.gradle.kts               # 루트 — subprojects { 툴체인·repositories·useJUnitPlatform }
├── gradle/libs.versions.toml      # 버전 카탈로그(3장에서 도입: jline·picocli 추가)
├── gradlew, gradle/wrapper/…      # Gradle 8.10 wrapper
└── agent-cli/
    ├── build.gradle.kts           # jackson·jline·picocli + 테스트(junit5·assertj)
    └── src/
        ├── main/java/agent/
        │   ├── warmup/            # 2장에서 누적: OpenAiChat(REPL이 사용), Main(구 진입점)
        │   └── cli/
        │       ├── Main.java                    # picocli 런처(정식 진입점)
        │       ├── bootstrap/{CliApplication,Bootstrap,Session}.java
        │       ├── render/Renderer.java         # 텍스트→ANSI(색)만 담당
        │       └── repl/Repl.java               # READ-EVAL-PRINT 루프 + 멀티턴 기억 + runOnce(1회 실행)
        └── test/java/agent/
            ├── warmup/OpenAiChatTest.java       # 2장 누적
            └── cli/render/RendererTest.java     # DumbTerminal로 렌더 검증(키 불필요)
```

## 테스트 (API 키 불필요)

렌더링을 `render`에 가둔 보상으로, "텍스트 → 화면 글자" 변환만 더미 터미널(`DumbTerminal`)로 떼어 검증한다. `Repl`(네트워크·키 필요)은 5장에서 페이크 `LlmClient`로 테스트 가능해진다.

```bash
./gradlew test
```

## 대화 실행 (API 키 필요)

```bash
export OPENAI_API_KEY="sk-..."
./gradlew run --args="자바에서 record가 뭐야? 한 줄로."   # 비대화형 1회 실행 — 한 번 답하고 종료
./gradlew run
# 터미널 코딩 에이전트  ·  /exit 로 종료
# › 자바에서 sealed interface가 뭐야? 한 줄로.
# ...
# › /exit
# 안녕히 가세요 👋
```

> `./gradlew run`은 표준 입력을 잘 못 넘길 때가 있다. 입력이 막히면 배포 스크립트로 직접 실행한다(부록 A.5):
> ```bash
> ./gradlew :agent-cli:installDist
> ./agent-cli/build/install/agent-cli/bin/agent-cli   # 진짜 TTY에서 REPL
> ```
> 참고: TTY가 아닌 곳(파이프·일부 IDE 콘솔)에서 실행하면 JLine이 `Unable to create a system terminal, creating a dumb terminal` 경고를 찍고 더미 터미널로 떨어진다 — **정상이다**(색·라인편집만 빠진다). 진짜 터미널이나 위 `installDist` 경로에서는 뜨지 않는다.

## 참고

- 기본 모델 `gpt-5.4-mini`는 자리표시자다 — 실제 호출 시 현재 OpenAI 모델 id로 교체(부록 A.4).
- Ctrl+D로 종료, Ctrl+C는 현재 줄 취소. 실행 중 LLM 호출의 *진짜* 인터럽트는 14장.
