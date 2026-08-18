# 9장 코드 — 파일 읽기 도구와 멀티모달

책 9장의 산출물. 첫 번째 진짜 도구인 **`ReadTool`**(라인 번호·범위 읽기·바이트 한도·중복 회피)과, "수정 전 반드시 Read 선행" 규칙(10장)을 받쳐 줄 **`FileStateCache`**를 만든다. 출력은 파일 종류별로 갈래지므로 `ReadResult` sealed 타입으로 표현한다.

```
ch09/                                          # ch08 위에 누적
├── gradle/libs.versions.toml                  # ※ + jackson-jdk8
├── agent-core/build.gradle.kts                # ※ + implementation(jackson-jdk8)  ← Optional 역직렬화
├── agent-core/src/main/java/agent/
│   ├── message/Json.java                      # ※ + Jdk8Module 등록
│   └── tool/
│       ├── ToolContext.java                   # ※ + fileState 필드(3-arg)
│       └── builtin/
│           ├── FileStateCache.java            # ★ LRU(100) 장부 + FileState(content·mtime·offset·limit·partialView)
│           ├── ReadResult.java                # ★ sealed: Text/Image/Notebook/Pdf/FileUnchanged
│           └── ReadTool.java                  # ★ 검증·범위·바이트한도·dedup·이미지·라인번호
└── agent-core/src/test/java/agent/tool/builtin/
    └── ReadToolTest.java                      # ★ 라인번호·dedup·검증·바이너리·음수limit·이미지상한·Optional 역직렬화

  ★ 신규  ·  ※ 9장에서 변경
```

## 이 장에서 굳힌 규칙

- **fileState 장부**: Read가 (내용·mtime·범위)를 적고, 10장 Write/Edit가 확인한다(전체 읽기만 dedup; 부분 뷰 제외).
- **입력 방어**: `offset`/`limit`은 1 이상이어야 하며(`validateInput`), `call`도 `Math.max(0, limit)`로 `subList` 예외를 막는다.
- **바이너리 안전**: 비-UTF-8 파일은 `MalformedInputException`을 잡아 읽기 쉬운 오류로 바꾼다.
- **이미지 상한**: 이미지는 범위 읽기가 없으므로 읽기 전에 `MAX_IMAGE_BYTES`(5MB)로 조건 없이 거른다.
- **이미지 MIME**: `jpg`는 `image/jpeg`로 정규화(`image/jpg`는 API가 거부).
- **빈 결과 설명**: 빈 파일·offset 초과는 빈 문자열 대신 이유를 알린다.

> Read는 작업 디렉터리에 *갇혀 있지 않다*(의도) — 접근 통제는 16장 권한 시스템의 몫이다.

## 빠른 시작

```bash
cd ch09
./gradlew test          # 키 불필요 — Read 도구·캐시·검증(JDK 21)
```
