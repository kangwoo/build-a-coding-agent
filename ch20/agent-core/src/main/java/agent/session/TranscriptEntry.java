package agent.session;

import agent.message.Message;

/**
 * JSONL 한 줄에 대응하는 저장 단위. 메시지에 부모(parentUuid)·세션 메타를 곁들인다.
 * 4장 Message가 이미 Jackson 다형 직렬화를 지원하므로 그대로 감싼다.
 * parentUuid가 null이면 Json.MAPPER의 NON_NULL 설정으로 직렬화에서 생략된다(첫 줄).
 */
public record TranscriptEntry(Message message, String parentUuid, String sessionId) {}
