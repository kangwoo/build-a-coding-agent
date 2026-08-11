package agent.cli.repl;

import agent.cli.bootstrap.LlmClients;
import agent.cli.bootstrap.Session;
import agent.cli.render.Renderer;
import agent.exec.CancellationToken;
import agent.llm.LlmClient;
import agent.llm.LlmRequest;
import agent.message.Message;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class Repl {
    private static final String SYSTEM_PROMPT =
            "You are a terminal coding agent. Be concise and helpful.";   // 18장에서 본격 조립
    private static final String DEFAULT_MODEL = "gpt-5.4-mini";           // 상위 모델은 "gpt-5.4" (최신 id로 교체 가능)

    private final Session session;
    private final LlmClient llm;
    private final List<Message> history = new ArrayList<>();

    private String oneShot;   // null이 아니면 비대화형 1회 실행 — run()이 READ 루프 대신 이 프롬프트 하나만 처리한다

    public Repl(Session session, String provider) {
        this.session = session;
        this.llm = LlmClients.forProvider(provider);
    }

    public void run() throws IOException {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            Renderer renderer = new Renderer(terminal);
            if (oneShot != null) {                       // 비대화형 1회 — 배너·READ 루프 없이 한 번 처리하고 끝
                history.add(Message.UserMessage.of(oneShot));
                converse(renderer);
                return;
            }
            renderer.banner();

            while (true) {
                String input;
                try { input = reader.readLine("› "); }
                catch (EndOfFileException eof) { break; }            // Ctrl+D → 종료
                catch (UserInterruptException ui) { continue; }      // Ctrl+C → 현재 줄 버림

                if (input == null) break;
                String text = input.strip();
                if (text.isEmpty()) continue;
                if (text.equals("/exit") || text.equals("/quit")) break;

                history.add(Message.UserMessage.of(text));
                converse(renderer);                       // ← 임시 호출이 LlmClient로
            }
            renderer.goodbye();
        }
    }

    /** 비대화형 1회 실행: run()과 같은 조립을 거쳐 프롬프트 하나만 처리하고 바로 끝낸다. */
    public void runOnce(String prompt) throws IOException {
        this.oneShot = prompt;
        run();
    }

    /** 한 번의 모델 호출: 응답 전체를 받아 그리고, 기록에 추가. */
    private void converse(Renderer renderer) {
        LlmRequest req = LlmRequest.chat(DEFAULT_MODEL, SYSTEM_PROMPT, history);
        try {
            Message.AssistantMessage reply = llm.create(req, CancellationToken.none());
            renderer.message(reply);                              // 한꺼번에 화면에(4장 message API)
            history.add(reply);                                   // 다음 턴 맥락에 포함
        } catch (RuntimeException ex) {
            renderer.message(Message.NoticeMessage.of("요청 실패: " + ex.getMessage()));
            history.remove(history.size() - 1);               // 방금 넣은 user 메시지를 되돌린다(3장의 실패 롤백 유지)
        }
    }
}
