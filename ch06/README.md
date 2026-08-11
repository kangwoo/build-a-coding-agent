# 6장 코드 — 스트리밍: SSE와 가상 스레드

[`chapters/06-스트리밍-SSE와-가상스레드.md`](../../chapters/06-스트리밍-SSE와-가상스레드.md)의 산출물. 5장의 비스트리밍 `LlmClient` 위에 **스트리밍**을 얹는다. SSE(Server-Sent Events) 파싱, 가상 스레드 기반 이벤트 흐름(`EventStream`), 공통 이벤트 어휘(`StreamEvent` 7종), 흐르는 이벤트를 최종 메시지로 모으는 누산기. REPL이 실제 모델에 붙어 **응답이 한 글자씩 흘러나오는 진짜 스트리밍 채팅**이 된다.

```
ch06/                                         # ch05 위에 누적
├── agent-core/src/main/java/agent/
│   ├── llm/
│   │   ├── LlmClient.java                    # ※ + stream() 한 메서드(6장)
│   │   ├── EventStream.java                  # ★ Iterable+AutoCloseable 이벤트 흐름
│   │   ├── EventStreams.java                 # ★ 가상 스레드 + BlockingQueue 생산자/소비자
│   │   ├── StreamCancelledException.java     # ★ 소비 인터럽트 전용 예외(IllegalStateException 계보와 구분)
│   │   ├── StreamEvent.java                  # ★ 공통 어휘 7종 + Delta(Text/Thinking/Signature/InputJson)
│   │   ├── AssistantMessageAccumulator.java  # ★ StreamEvent → AssistantMessage 조립(provider 중립)
│   │   └── openai/OpenAiClient.java          # ※ + stream()/translate()/TranslateState(SSE→공통 이벤트), create() 인터럽트→취소 승격
│   └── message/ …                            # 4장 그대로
├── agent-core/src/test/java/agent/llm/
│   ├── EventStreamsTest.java                 # ★ 소비 인터럽트 → StreamCancelledException·플래그 복원(위장 금지)
│   └── openai/OpenAiStreamingTest.java       # ★ 가짜 SSE 서버로 스트림·조립·오류 경로 검증(키 불필요)
└── agent-cli/src/main/java/agent/cli/
    ├── render/Renderer.java                  # ※ + newline()/assistantChunk()/system()
    └── repl/Repl.java                        # ※ converse를 스트리밍 루프 + 누산기로 교체

  ★ 신규  ·  ※ 5장에서 변경
```

## 빠른 시작

```bash
cd ch06
./gradlew test          # 키 불필요 — 스트림 파싱·조립·오류 경로 검증
export OPENAI_API_KEY="sk-..."
./gradlew run           # 입력하면 응답이 한 글자씩 흘러나온다
```

> JDK 21만 있으면 된다(가상 스레드·텍스트 블록 사용). `./gradlew test`는 `OPENAI_API_KEY` 없이 통과한다.
