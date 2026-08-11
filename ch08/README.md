# 8장 코드 — 입력 스키마와 2단계 검증

[`chapters/08-입력-스키마와-2단계-검증.md`](../../chapters/08-입력-스키마와-2단계-검증.md)의 산출물. 자바 record로부터 **JSON Schema를 자동 생성**해 모델에게 도구 사용법을 알려주고, 모델이 보낸 입력을 **두 단계**(구조·타입 검증 → 의미 검증)로 거른다.

```
ch08/                                         # ch07 위에 누적
└── agent-core/src/main/java/agent/tool/
    ├── schema/
    │   ├── Desc.java                         # ★ @Desc(RECORD_COMPONENT) — 스키마 description
    │   ├── JsonSchemas.java                  # ★ record→JSON Schema(메모이즈, record-only 가드)
    │   └── SchemaValidator.java              # ★ 객체·필수(null=없음)·미지필드·타입 검증
    ├── Tool.java                             # ※ inputSchema() 기본값을 자동 생성으로 교체
    ├── ToolExecutor.java                     # ※ ② 단계를 ②-a(스키마)·②-b(역직렬화)로 분리
    └── builtin/EchoTool.java                 # ※ Input에 @Desc 부착

  agent-core/src/test/java/agent/tool/schema/
    └── SchemaTest.java                       # ★ 생성·필수/미지필드/타입 거부·boolean&Optional 선택

  ★ 신규  ·  ※ 8장에서 변경
```

## 검증 규칙

- **단일 진실원**: 스키마 속성명·타입·required는 모두 record에서 파생(이름은 필드명 그대로 — camelCase).
- **선택 필드**: `Optional<T>`와 primitive `boolean`(없으면 false)은 required에서 제외. 그 밖은 필수.
- **타입 강제**: `SchemaValidator`가 선언한 `type`과 실제 JSON 종류를 대조 — Jackson의 조용한 강제변환(`42`→`"42"`)을 막는다.
- **봉투 통일**: 구조/의미 실패 모두 `<tool_use_error>` 안에 담기고, 구조 실패는 `InputValidationError:` 접두어.

## 빠른 시작

```bash
cd ch08
./gradlew test          # 키 불필요 — 스키마 생성·2단계 검증(JDK 21)
```

> `Optional<T>`를 실제로 *역직렬화*하려면 Jackson jdk8 모듈이 필요하다 — Optional을 처음 쓰는 9장에서 의존성과 매퍼에 더한다. 8장은 스키마 생성만 하므로 모듈 없이 통과한다.
