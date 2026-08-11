# 7장 코드 — 도구 계약: Tool 인터페이스와 안전한 기본값

[`chapters/07-도구-계약과-안전한-기본값.md`](../../chapters/07-도구-계약과-안전한-기본값.md)의 산출물. 앞으로 만들 모든 도구가 공통으로 따를 **`Tool<I,O>` 계약**(필수 메서드 + 안전한 `default` 기본값)과, `tool_use` 한 개를 받아 `tool_result` 한 개로 만드는 **단일 실행 파이프라인**(`ToolExecutor`)을 만든다. 더미 도구 `EchoTool`로 계약이 도는지 확인한다.

```
ch07/                                         # ch06 위에 누적
└── agent-core/src/main/java/agent/tool/      # ★ 신규 패키지
    ├── Tool.java                             # I=입력 record, O=출력. 필수 + 안전한 default 기본값
    ├── ToolContext.java                      # 작업 디렉터리·취소 토큰(9·14장에서 필드 추가)
    ├── ToolResult.java                       # 구조화된 실행 결과(data)
    ├── ValidationResult.java                 # sealed Ok/Fail(message,errorCode)
    ├── PermissionResult.java                 # sealed Allow/Ask/Deny(16장에서 확장)
    ├── ToolRegistry.java                     # 이름·alias로 도구 조회
    ├── ToolExecutor.java                     # ②역직렬화→③검증→④권한→⑤실행→⑥매핑, 모든 실패를 tool_result로
    └── builtin/EchoTool.java                 # 첫 더미 도구(읽기 전용·동시 안전)

  agent-core/src/test/java/agent/tool/
    └── ToolExecutorTest.java                 # 모든 경로 검증: 정상·빈입력·역직렬화/검증/권한/실행 실패
```

## 안전 설계 핵심

- **fail-closed 기본값**: `isReadOnly`/`isConcurrencySafe`/`isDestructive`는 기본 `false`(모르면 보수적으로).
- **모든 경로가 `tool_result`**: 빈 입력(JSON `null`)·역직렬화 실패·검증 실패·권한 거부·실행 예외를 전부 `tool_result(is_error)`로. 어떤 예외도 `runToolUse` 밖으로 새지 않는다.
- **Allow만 통과**: `Deny`뿐 아니라 `Ask`도 보수적으로 막는다(대화형 승인은 16장).
- **`<tool_use_error>` 통일**: 모든 실패를 같은 봉투로 감싸 모델이 인식하게 한다.

## 빠른 시작

```bash
cd ch07
./gradlew test          # 키 불필요 — 도구 계약·실행 파이프라인 검증(JDK 21)
```

> ① 도구 찾기(레지스트리 조회)는 호출자(12·13장 엔진/오케스트레이터)의 몫이라, `ToolExecutor`는 이미 찾은 도구를 받아 ②부터 시작한다.
