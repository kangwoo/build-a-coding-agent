# 20장 코드 — append-only JSONL 영속성과 세션 복원

책 20장의 산출물. 대화를 **append-only JSONL**로 디스크에 한 줄씩 쌓고, 재시작 후 `--resume`으로 이전 세션을 이어 간다. 12장에 비워 둔 `AgentServices` 이음매의 마지막 칸을 영속성(`TranscriptStore`)으로 채워, 엔진이 메시지를 추가할 때마다 기록하고 시작 시 복원한다. 세션 정체성(`SessionContext`)은 `sessionId`와 `projectDir`를 한 덩어리로 원자적으로 다룬다.

```
ch20/                                           # ch19 위에 누적
├── agent-core/src/main/java/agent/
│   ├── session/                                # ★ 새 패키지 — 세션 정체성·대화 영속성
│   │   ├── SessionContext.java                 # ★ 세션 정체성(sessionId+projectDir)을 AtomicReference로 원자 교체 + 경로 NFC 정규화
│   │   ├── TranscriptEntry.java                # ★ JSONL 한 줄 = Message + parentUuid + sessionId
│   │   └── TranscriptStore.java                # ★ append-only JSONL: 가상 스레드 디바운스 배치 쓰기·dedup·materialize·load 복원
│   └── engine/
│       ├── AgentServices.java                  # ※ 다섯 번째 필드 transcript 추가(null이면 기록 안 함)
│       └── AgentEngine.java                    # ※ resume()로 복원 + 메시지 3지점에서 record()로 영속화
└── agent-cli/src/main/java/agent/cli/
    ├── bootstrap/CliApplication.java           # ※ --resume/--continue 옵션 추가 → Repl로 전달
    └── repl/Repl.java                          # ※ SessionContext 조립·switchSession·TranscriptStore try-with-resources

  agent-core/src/test/java/agent/
    ├── session/SessionContextTest.java         # ★ sanitize NFC 정규화·switchSession 원자 교체
    ├── session/TranscriptStoreTest.java        # ★ 저장→복원 라운드트립·dedup
    └── engine/AgentEnginePersistenceTest.java  # ★ resume 시드·transcript record 검증(가짜 모델)

  ★ 신규  ·  ※ 20장에서 변경
```

## 영속성의 핵심

- **append-only JSONL**: 메시지가 생길 때마다 파일 *끝*에 JSON 한 줄을 덧붙이고 중간은 고치지 않는다 — 쓰기가 원자적이고 크래시에 강하다. 한 줄 = `TranscriptEntry`(`message` + `parentUuid` + `sessionId`).
- **쓰기는 핫 패스 밖으로**: `record()`는 엔진 스레드에서 dedup·`parentUuid` 연결만 끝내고 직렬화된 줄을 큐에 넣는다. 전용 가상 스레드가 100ms 디바운스로 모아 append하고, 첫 메시지에서 비로소 파일을 materialize한다(빈 세션이 `--resume` 목록을 더럽히지 않게).
- **세션 정체성은 원자적으로**: `SessionContext`는 `sessionId`+`projectDir`를 한 record로 묶어 `AtomicReference`로 통째 교체한다. 따로 바꾸면 그 틈에 `transcriptPath()`가 엉뚱한 디렉터리를 가리킨다. 경로는 글자·숫자 외를 `-`로 바꾸고(유니코드 글자 보존 — 한글 경로가 전부 대시로 붕괴하지 않게) macOS 유니코드 차이를 막으려 **NFC 정규화**한다.

## 엔진·복원 연결

- **엔진 연결**: `AgentServices`에 `transcript`를 다섯 번째 필드로 *뒤에* 더한다(null이면 기록 안 함 — 기존 호출부를 깨지 않는다). 엔진은 메시지를 더하는 세 지점(여는 user 프롬프트·assistant 응답·`tool_result` user)에서 `record()`를 함께 부른다. 압축(19장)으로 메시지가 교체돼도 `uuid`가 같으면 dedup이 다시 쓰지 않는다.
- **재개(resume)**: `--resume`이면 REPL이 `switchSession`으로 정체성을 *먼저* 바꿔 경로를 확정한 뒤 `TranscriptStore.load`로 파일 순서(=시간순) 메시지를 읽어 `engine.resume()`에 시드한다. 복원 시 18장 컨텍스트는 재주입하지 않고(복원된 첫 메시지에 이미 있다), `parentUuid`는 기록만 하고 복원엔 쓰지 않는다.

## 빠른 시작

```bash
cd ch20
./gradlew test          # 저장→복원 라운드트립·dedup·세션 정체성 검증(JDK 21, API 키 불필요)
```
