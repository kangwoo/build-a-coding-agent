package agent.context.compact;

import agent.message.ContentBlock;
import agent.message.ContentBlock.*;
import agent.message.Message;

import java.util.List;

public final class TokenEstimator {
    private TokenEstimator() {}

    /** 앵커(마지막 usage) + 이후 메시지 rough 추정. */
    public static long estimate(List<Message> messages) {
        long anchor = 0;
        int anchorIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.AssistantMessage am && am.usage().inputTokens() > 0) {
                // OpenAI 기준: inputTokens가 이미 캐시 토큰을 포함하므로 input+output으로 충분하다
                // (Anthropic은 캐시를 별도 보고 → 24장 합류 시 totalTokens()로 일반화).
                anchor = am.usage().inputTokens() + am.usage().outputTokens();
                anchorIdx = i;
                break;
            }
        }
        long after = 0;
        for (int i = anchorIdx + 1; i < messages.size(); i++) after += rough(messages.get(i));
        return anchor + after;
    }

    static long rough(Message m) {
        long chars = 0, images = 0;
        for (ContentBlock b : m.content()) {
            switch (b) {
                case TextBlock t -> chars += t.text().length();
                case ThinkingBlock t -> chars += t.thinking().length();
                case ToolUseBlock u -> chars += u.input().toString().length();
                case ToolResultBlock r -> chars += textLen(r.content());
                case ImageBlock ignored -> images++;
            }
        }
        long tokens = chars / 4 + images * 2000;     // 이미지 고정 2000
        return tokens * 4 / 3;                         // 4/3 보수 패딩(과소추정으로 압축이 늦는 것 방지)
    }

    private static long textLen(List<ContentBlock> blocks) {
        long n = 0;
        for (ContentBlock b : blocks) if (b instanceof TextBlock t) n += t.text().length();
        return n;
    }
}
