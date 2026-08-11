package agent.context.compact;

import agent.exec.CancellationToken;
import agent.llm.*;
import agent.message.*;
import agent.message.ContentBlock.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ContextManagerTest {

    /** 항상 예외를 던지는 가짜 모델(서킷 브레이커 검증용). */
    static LlmClient throwingModel() {
        return new LlmClient() {
            public EventStream<StreamEvent> stream(LlmRequest r, CancellationToken c) { throw new RuntimeException("boom"); }
            public Message.AssistantMessage create(LlmRequest r, CancellationToken c) { throw new RuntimeException("boom"); }
            public LlmCapabilities capabilities() { return new LlmCapabilities(false,false,false,false); }
            public String name() { return "throwing"; }
        };
    }

    private static Message.UserMessage userWithToolResult(String text) {
        return Message.UserMessage.ofBlocks(List.of(ToolResultBlock.ok("tu", text)));
    }
    private static String toolResultText(Message m) {
        return ((TextBlock) ((ToolResultBlock) m.content().get(0)).content().get(0)).text();
    }

    @Test
    void should_compact_only_when_estimate_exceeds_threshold() {
        // 윈도우 34000 → threshold = 34000 - min(20000,128000) - 13000 = 1000
        var cm = new ContextManager(34_000, throwingModel(), "m");

        List<Message> small = new ArrayList<>(List.of(Message.UserMessage.of("hi")));
        assertThat(cm.shouldCompact(small)).isFalse();   // estimate ~0 < 1000

        List<Message> big = new ArrayList<>(List.of(Message.UserMessage.of("x".repeat(8000))));
        assertThat(cm.shouldCompact(big)).isTrue();      // estimate ~2666 > 1000
    }

    @Test
    void circuit_breaker_stops_after_three_failures() {
        // 윈도우 1000 → threshold 음수 → 항상 임계치 초과(서킷 브레이커 전까지 true)
        var cm = new ContextManager(1_000, throwingModel(), "m");
        List<Message> msgs = new ArrayList<>(List.of(Message.UserMessage.of("hi")));

        assertThat(cm.shouldCompact(msgs)).isTrue();
        for (int i = 0; i < 3; i++)                      // 3회 실패 — 실패는 빈 값(원본은 호출자가 유지)
            assertThat(cm.fullCompact(msgs, Optional.empty(), CancellationToken.none())).isEmpty();
        assertThat(cm.shouldCompact(msgs)).isFalse();    // 서킷 브레이커 작동
    }

    @Test
    void micro_compact_clears_old_tool_results_but_keeps_recent() {
        var cm = new ContextManager(1_000_000, throwingModel(), "m");
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 6; i++) msgs.add(userWithToolResult("결과 " + i));   // RECENT_KEEP=4

        cm.microCompact(msgs);

        // cutoff = 6 - 4 = 2 → index 0,1 비워짐, 2~5 보존
        assertThat(toolResultText(msgs.get(0))).isEqualTo("[오래된 결과가 비워짐]");
        assertThat(toolResultText(msgs.get(1))).isEqualTo("[오래된 결과가 비워짐]");
        assertThat(toolResultText(msgs.get(2))).isEqualTo("결과 2");
        assertThat(toolResultText(msgs.get(5))).isEqualTo("결과 5");
    }

    @Test
    void full_compact_replaces_with_summary_on_success() {
        LlmClient summarizer = new LlmClient() {
            public EventStream<StreamEvent> stream(LlmRequest r, CancellationToken c) { throw new UnsupportedOperationException(); }
            public Message.AssistantMessage create(LlmRequest r, CancellationToken c) {
                return Message.AssistantMessage.of(List.of(new TextBlock("요약된 내용")), Usage.EMPTY, "end_turn");
            }
            public LlmCapabilities capabilities() { return new LlmCapabilities(false,false,false,false); }
            public String name() { return "sum"; }
        };
        var cm = new ContextManager(1_000_000, summarizer, "m");
        Message question = Message.UserMessage.of("더 긴 대화");   // 호출자(엔진)가 아는 진행 중 질문
        List<Message> msgs = new ArrayList<>(List.of(
                Message.UserMessage.of("긴 대화"), question));

        List<Message> out = cm.fullCompact(msgs, Optional.of(question), CancellationToken.none()).orElseThrow();
        assertThat(out).hasSize(3);   // [경계 마커, <summary>, 재부착된 질문]
        assertThat(out.get(0)).isInstanceOf(Message.NoticeMessage.class);
        String summaryText = ((TextBlock) out.get(1).content().get(0)).text();
        assertThat(summaryText).contains("<summary>").contains("요약된 내용");
        String reattached = ((TextBlock) out.get(2).content().get(0)).text();
        assertThat(reattached).isEqualTo("더 긴 대화");   // 진행 중이던 질문은 원문 그대로 되붙는다

        // 턴 경계 호출자(진행 중 질문 없음)가 빈 값을 넘기면 재부착 없이 [경계 마커, <summary>]다.
        assertThat(cm.fullCompact(msgs, Optional.empty(), CancellationToken.none()).orElseThrow()).hasSize(2);
    }
}
