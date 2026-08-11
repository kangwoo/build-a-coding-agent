# 10장 코드 — 파일 쓰기·편집과 Read-before-Write 안전 모델

[`chapters/10-파일-쓰기-편집과-안전모델.md`](../../chapters/10-파일-쓰기-편집과-안전모델.md)의 산출물. `WriteTool`(전체 쓰기)과 `EditTool`(정확한 부분 치환)을 만들되, 이 장의 절반은 "**언제 쓰면 안 되는지**"다 — 9장 `FileStateCache`를 진실원으로 한 **Read 선행 + staleness** 안전 모델.

```
ch10/                                          # ch09 위에 누적
├── gradle/libs.versions.toml                  # ※ + java-diff-utils 4.12
├── agent-core/build.gradle.kts                # ※ + implementation(java-diff-utils)
└── agent-core/src/main/java/agent/tool/builtin/
    ├── FileStateCache.java                    # ※ + changedSinceRead(mtime + 내용 fallback)
    ├── WriteTool.java                         # ★ LF 통일·Read 선행·staleness·critical section
    └── EditTool.java                          # ★ 정확한 치환·줄끝(다수결) 보존·Hunk diff·매치 수 검증

  agent-core/src/test/java/agent/tool/builtin/
    ├── WriteToolTest.java                     # ★ 생성·Read 선행 거부·외부 변경 거부·Write→Edit
    └── EditToolTest.java                      # ★ Read 선행·모호 매치·CRLF 보존·가짜 mtime 흡수

  ★ 신규  ·  ※ 10장에서 변경
```

## 안전 모델 핵심

- **Read 선행**: 기존 파일 수정 전 `FileStateCache`에 읽은 기록이 있어야 한다(없으면 거부).
- **staleness(`changedSinceRead`)**: 읽은 이후 mtime이 늦으면 거부하되, **내용이 같으면**(Windows·동기화의 가짜 mtime 튐) 통과 — 9장 `FileState.content`가 여기서 쓰인다. (한계: 같은 ms 변경은 못 잡음.)
- **두 번 검사**: `validateInput`(사전 점검) + `call`의 critical section 재검사(최종 방어선). Write·Edit 둘 다.
- **줄끝**: Write는 LF 통일, Edit는 *다수* 스타일 보존(normalize-then-join으로 `\r\r\n` 방지).
- **치환 안전**: 매치 0/모호(다중)는 거부, 치환이 사라지면(`edited==original`) 거부.

> Write/Edit는 작업 디렉터리에 *갇혀 있지 않다* — 파괴적이라 더 위험하다. 접근 통제는 16장 권한 시스템의 몫(§10.7).

## 빠른 시작

```bash
cd ch10
./gradlew test          # 키 불필요 — Write/Edit·안전 모델 검증(JDK 21)
```
