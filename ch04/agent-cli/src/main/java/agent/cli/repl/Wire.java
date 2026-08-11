package agent.cli.repl;

import agent.cli.render.Renderer;
import agent.message.Message;

import java.util.List;
import java.util.Map;

/** 2장 warm-up 클라이언트가 먹는 wire 포맷으로의 임시 어댑터. 5장에서 사라진다. */
final class Wire {
    private Wire() {}

    static List<Map<String, String>> toChatMessages(List<Message> conversation) {
        return conversation.stream()
                .map(m -> Map.of("role", role(m), "content", Renderer.plainText(m)))
                .toList();
    }

    private static String role(Message m) {
        return switch (m) {
            case Message.UserMessage u      -> "user";
            case Message.AssistantMessage a -> "assistant";
            case Message.NoticeMessage s    -> "notice";
        };
    }
}
