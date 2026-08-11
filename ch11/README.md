# 11장 코드 — 코드베이스 탐색(Glob/Grep)과 웹 도구

[`chapters/11-코드베이스-탐색과-웹-도구.md`](../../chapters/11-코드베이스-탐색과-웹-도구.md)의 산출물. `ripgrep`을 외부 프로세스로 부르는 **`GlobTool`(파일명)·`GrepTool`(내용)**, 그리고 같은 "외부 호출" 골격 위의 **`WebFetchTool`**·`SearchBackend`. 이 외부 프로세스 패턴은 15장 Bash의 토대다.

```
ch11/                                           # ch10 위에 누적
└── agent-core/src/main/java/agent/tool/
    ├── process/RipGrep.java                    # ★ rg 래퍼 — 종료코드 0/1/2+, 타임아웃, stdout·stderr 드레인
    └── builtin/
        ├── GlobTool.java                       # ★ rg --files --glob, 상대경로화(방어적), 100개 제한
        ├── GrepTool.java                       # ★ 출력모드 content/files/count, -- 패턴 보호, 250줄 제한
        ├── WebFetchTool.java                   # ★ HttpClient(리다이렉트·상태코드·http/https) + 태그 strip
        └── SearchBackend.java                  # ★ 웹 검색 백엔드 인터페이스(WebSearch는 계약만)

  agent-core/src/test/java/agent/tool/builtin/
    ├── GrepToolTest.java                       # ★ 매치·무매치=정상·'-' 패턴 보호 (@EnabledIf rg)
    └── GlobToolTest.java                       # ★ glob 필터 + 상대경로화 (@EnabledIf rg)

  ★ 신규
```

## 외부 프로세스의 세 함정(+보강)

- **종료코드 0/1/2+**: 0=매치, 1=무매치(정상), 2+만 진짜 오류. stderr를 오류 메시지에 실어 "왜"를 남긴다.
- **타임아웃 → `destroyForcibly`** 후 *예외*(빈 결과로 돌리면 "매치 없음"으로 오해).
- **stdout·stderr 둘 다 드레인**(한쪽만 읽으면 파이프가 차서 데드락 — 리뷰가 stderr 데드락을 실증).
- **`-` 시작 패턴 보호**: 위치 인자 앞 `--`로 옵션 파싱 종료.
- **WebFetch는 SSRF 표면**: http/https만 허용(여기서), 리다이렉트 추적, 상태코드 검사. 본격 정책은 16장.

> 검색은 `.gitignore`를 존중한다 — 빌드/생성 파일은 조용히 빠진다. 패턴은 상대형이어야 한다.

## 빠른 시작

```bash
cd ch11
./gradlew test          # rg 있으면 Glob/Grep 실행, 없으면 @EnabledIf로 건너뜀(JDK 21)
```
