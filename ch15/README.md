# 15장 코드 — 셸 실행 도구(Bash)와 프로세스 수명주기

[`chapters/15-셸-실행-도구-Bash.md`](../../chapters/15-셸-실행-도구-Bash.md)의 산출물. 가장 강력하고 위험한 도구인 **`BashTool`** — *아무* 셸 명령이나 실행하고 합쳐진 출력(merged fd)을 캡처한다. 11장 외부 프로세스 패턴(`RipGrep`: 가상 스레드 드레인·타임아웃) 위에 14장 취소(`onCancel`/`isInterrupt`)를 얹어, 프로세스 수명주기(타임아웃·강제 종료·tree-kill·종료코드 해석)를 정밀하게 다룬다. 권한은 16장에서 붙고, 지금은 무확인 실행이다.

```
ch15/                                          # ch14 위에 누적
└── agent-core/src/main/java/agent/tool/builtin/
    ├── BashTool.java                          # ★ 셸 실행 — merged fd·타임아웃·tree-kill·종료코드 해석·취소(14장)
    └── BuiltinTools.java                      # ※ + BashTool 등록(7~11장 도구 옆에 Bash)

  agent-core/src/test/java/agent/tool/builtin/
    └── BashToolTest.java                      # ★ 출력 캡처·merged fd·타임아웃·출력 절단·종료코드 해석

  ★ 신규  ·  ※ 15장에서 변경
```

## 셸 실행의 함정

- **merged fd**: `redirectErrorStream(true)`로 stdout·stderr를 OS 수준에서 한 스트림으로 합친다. 자바에서 따로 읽어 이어 붙이면 줄 순서(interleaving)가 뒤섞인다.
- **타임아웃 → 강제 종료**: 기본 2분·최대 10분. `waitFor(timeout)`이 false면 `treeKill` 후 잠깐 더 기다린다 — 방치하면 멈춘 명령이 영원히 매달린다.
- **tree-kill**: 셸이 남긴 손자 프로세스(`sleep 30 &`)는 부모만 죽여선 안 사라진다. `ProcessHandle.descendants()`까지 강제 종료해 좀비·자원 누수를 막는다.
- **출력 상한(30K)**: 상한에서 저장은 멈추되 스트림은 끝까지 빨아들인다(버리더라도) — 안 그러면 파이프가 차서 프로세스가 막힌다. 실제로 잘렸을 때만 절단 마커를 붙인다.
- **취소 전파(14장)**: interrupt는 직속 프로세스만 SIGTERM(손자는 안 건드림), 일반 취소·타임아웃은 손자까지 tree-kill로 강제 종료한다. 부분 출력은 별도 drain 스레드가 종료 전까지 잡은 만큼 보존된다.
- **종료코드 해석**: `exit ≠ 0 = 오류`로 뭉개지 않는다. grep·diff·test·find의 exit 1은 정상(무매치/차이/거짓), 2+만 진짜 오류다. `Output`에 `command`를 실어 `mapResult`가 첫 단어(`firstWord`)로 명령별 해석을 한다(파이프라인이면 첫 단어만 보는 한계).

> Bash는 작업 디렉터리(`ctx.workingDir()`)에서 *아무* 명령이나 **무확인 실행**한다 — 이 책에서 가장 큰 보안 표면이다. 지금은 `checkPermissions` 기본값(allow)이라 의도적으로 열려 있고, 16장 권한 게이트(allow/deny/ask)를 붙여야 실전 안전성이 생긴다.

## 빠른 시작

```bash
cd ch15
./gradlew test          # bash 있으면 실행·merged·타임아웃·절단·종료코드 검증, 없으면 @EnabledIf로 건너뜀(JDK 21, 키 불필요)
```
