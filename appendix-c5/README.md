# 부록 C.5 코드 — 비동기 서브에이전트 완성(백그라운드 태스크)

책 부록 C 「확장 아이디어」 §C.5의 참조 구현. 23장에서 *골격*만 짚은 백그라운드 태스크를 동작하는 형태로 채운다 — 진행 출력을 디스크에 흘리며 **오프셋으로 증분 폴링**하는 저장소(`BackgroundTasks`)와, 그 위에 얹는 **`TaskOutput`·`TaskStop`** 도구다.

이 디렉터리는 **`ch25/` 스냅샷의 사본**에 아래 파일만 더한 것이다(번호 붙은 장의 코드는 건드리지 않는다 — 부록은 누적 스냅샷이 아니라 곁가지다).

```
appendix-c5/                                              # ch25 사본 + 아래 ★만 추가
└── agent-core/src/main/java/agent/subagent/
    ├── BackgroundTasks.java     # ★ 백그라운드 태스크 저장소 — 디스크 append 출력·바이트 오프셋 증분 읽기·분리된 취소·완료 우선 전이
    ├── TaskOutputTool.java      # ★ 새 출력을 오프셋부터 증분으로 가져오는 Tool
    └── TaskStopTool.java        # ★ 실행 중 태스크를 강제 종료(KILLED)하는 Tool

  agent-core/src/test/java/agent/subagent/
    ├── BackgroundTasksTest.java # ★ 완료·증분 오프셋(UTF-8 바이트)·stop이 뒤늦은 완료를 이김·실패 전이
    ├── TaskOutputToolTest.java  # ★ 상태/다음 오프셋 포맷·없는 태스크 메시지·스키마(offset 선택)
    └── TaskStopToolTest.java    # ★ 실행 중 종료·이미 끝난 태스크 no-op

  ★ 신규
```

## 설계의 핵심

- **디스크 append + 오프셋 증분 읽기**: 진행 출력을 메모리가 아니라 태스크별 파일(`<id>.out`)에 append하고, `output(id, offset)`이 `[offset, EOF)`만 읽어 *다음 오프셋*과 함께 돌려준다 — 큰 출력에도 메모리가 터지지 않는다. 오프셋은 **바이트** 단위지만, append가 항상 문자열 전체를 쓰므로 경계가 UTF-8 문자 중간을 가르지 않는다.
- **분리된 취소 토큰**: 각 태스크는 `CancellationToken.root()`로 *부모와 분리된* 토큰을 받는다. 메인을 Ctrl+C로 끊어도 백그라운드는 살아남고, **명시적 `stop`으로만** 죽는다(23장 동기 위임의 공유 취소와 반대).
- **완료 전이를 정리보다 먼저**: 종료 상태 전이와 `<task-notification>` 알림을 *먼저* 하고 외부 정리는 그 뒤에 둔다 — 결과를 기다리는 쪽이 즉시 깨어난다. `stop`은 작업이 실제로 멈추길 기다리지 않고 상태를 즉시 KILLED로 확정하며, 뒤늦게 끝난 작업 결과는 무시된다(중복 알림도 없다).
- **LLM과의 분리**: 저장소는 `Work`(= `(cancel, out) -> 요약`) 함수만 받는다. 그래서 서브에이전트든 셸 명령이든 무엇이든 백그라운드로 띄울 수 있고, 테스트는 **가짜 `Work`로 API 키 없이** 전 경로(띄움→증분 폴링→종료/실패/중단)를 검증한다.

> 라이브 REPL에 연결하는 마지막 이음매(비동기 `AgentTool` 분기, 두 도구 등록, 매 턴 `drainNotifications()` 표시)는 본문 §C.5에 정리해 두었다 — 그 부분만 와이어링하면 모델이 직접 백그라운드 작업을 띄우고 폴링한다.

## 빠른 시작

```bash
cd appendix-c5
./gradlew :agent-core:test --tests 'agent.subagent.BackgroundTasksTest' \
                           --tests 'agent.subagent.TaskOutputToolTest' \
                           --tests 'agent.subagent.TaskStopToolTest'
# 실제 MCP 서버·LLM·API 키 모두 불필요(JDK 21만 필요)
```
