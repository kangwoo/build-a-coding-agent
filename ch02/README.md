# 2장 코드 — OpenAI로 첫 응답 받기

[`chapters/02-OpenAI로-첫-응답-받기.md`](../../chapters/02-OpenAI로-첫-응답-받기.md)의 산출물을 그대로 담은, **실제로 동작하는** Gradle 프로젝트다.

```
ch02/
├── settings.gradle.kts             # rootProject "coding-agent", include("agent-cli")
├── gradlew, gradle/wrapper/…       # Gradle 8.10 wrapper
└── agent-cli/
    ├── build.gradle.kts            # jackson-databind(+ 테스트용 JUnit 5)
    └── src/
        ├── main/java/agent/warmup/
        │   ├── OpenAiChat.java     # HttpClient + Jackson 워밍업 클라이언트
        │   └── Main.java           # OPENAI_API_KEY 읽어 한 줄 보내는 진입점
        └── test/java/agent/warmup/
            └── OpenAiChatTest.java # JDK HttpServer 스텁으로 왕복 검증(키 불필요)
```

## 테스트 (API 키 불필요)

진짜 OpenAI 서버 대신 JDK 내장 `HttpServer`로 로컬 스텁을 띄워 왕복 전체를 검증한다. 네트워크·비용·키가 모두 필요 없다.

```bash
./gradlew test
```

## 실제 실행 (API 키 필요)

`gpt-5.4-mini`는 집필 시점 기준의 저렴한 범용 모델 id다. 모델 id는 자주 바뀌므로 동작하지 않으면 `OpenAiChat.DEFAULT_MODEL`을 현재 OpenAI 모델 id로 바꾼다(부록 A.4).

```bash
export OPENAI_API_KEY="sk-..."
./gradlew run --args="자바의 record를 한 줄로 설명해줘"
# AI: record는 불변 데이터를 담는 ...
```

> 키 없이 `./gradlew run`을 돌리면 가드가 동작해 안내 메시지를 출력하고 종료 코드 1로 끝난다(정상). 실제 대화는 키가 있을 때만 일어난다.

## 참고

- JDK 21 필요(`record`·텍스트 블록 등). wrapper가 Gradle 8.10을 자동으로 내려받는다.
- 이 디렉터리는 2장 시점의 스냅샷이다. 3장부터 정식 진입점·모듈 골격으로 자란다.
