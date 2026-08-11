package agent.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "role")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Message.UserMessage.class,      name = "user"),
    @JsonSubTypes.Type(value = Message.AssistantMessage.class, name = "assistant"),
    @JsonSubTypes.Type(value = Message.NoticeMessage.class,    name = "notice"),
})
public sealed interface Message
        permits Message.UserMessage, Message.AssistantMessage, Message.NoticeMessage {

    String uuid();
    Instant timestamp();
    List<ContentBlock> content();   // 모든 메시지를 블록 배열로 균일하게 다룬다

    /** 사용자 입력, 그리고 도구 결과(tool_result)도 user 역할로 담는다. */
    record UserMessage(String uuid, Instant timestamp, List<ContentBlock> content, boolean injected)
            implements Message {
        public static UserMessage of(String text) {
            return new UserMessage(UUID.randomUUID().toString(), Instant.now(),
                    List.of(new ContentBlock.TextBlock(text)), false);
        }
        public static UserMessage ofBlocks(List<ContentBlock> content) {
            return new UserMessage(UUID.randomUUID().toString(), Instant.now(), content, false);
        }
    }

    /** 모델의 응답. text/thinking/tool_use 블록과 usage·종료사유를 담는다. */
    record AssistantMessage(String uuid, Instant timestamp, List<ContentBlock> content,
                            Usage usage, String stopReason) implements Message {
        public static AssistantMessage of(List<ContentBlock> content, Usage usage, String stopReason) {
            return new AssistantMessage(UUID.randomUUID().toString(), Instant.now(),
                    content, usage, stopReason);
        }
    }

    /** 내부 알림/오류 표시용(LLM에 보내는 시스템 프롬프트가 아니다 — 그건 18장). */
    record NoticeMessage(String uuid, Instant timestamp, String text) implements Message {
        @Override public List<ContentBlock> content() {
            return List.of(new ContentBlock.TextBlock(text));   // 균일 접근을 위해 텍스트를 블록으로
        }
        public static NoticeMessage of(String text) {
            return new NoticeMessage(UUID.randomUUID().toString(), Instant.now(), text);
        }
    }
}
