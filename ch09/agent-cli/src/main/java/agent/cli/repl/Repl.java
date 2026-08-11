package agent.cli.repl;

import agent.cli.bootstrap.LlmClients;
import agent.cli.bootstrap.Session;
import agent.cli.render.Renderer;
import agent.exec.CancellationToken;
import agent.llm.AssistantMessageAccumulator;
import agent.llm.EventStream;
import agent.llm.LlmClient;
import agent.llm.LlmRequest;
import agent.llm.StreamEvent;
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

    /** 한 번의 모델 호출: 텍스트는 흘리고, 끝나면 최종 메시지를 기록에 추가. */
    private void converse(Renderer renderer) {
        LlmRequest req = LlmRequest.chat(DEFAULT_MODEL, SYSTEM_PROMPT, history);
        var acc = new AssistantMessageAccumulator();      // provider 중립 누산기
        boolean ok = false;

        try (EventStream<StreamEvent> stream = llm.stream(req, CancellationToken.none())) {
            for (StreamEvent e : stream) {
                if (e instanceof StreamEvent.ApiError err) {   // 오류는 누산기에 넣지 않고 바로 표시
                    renderer.system("API 오류(" + err.httpStatus() + "): " + err.message());
                    break;
                }
                acc.accept(e);
                if (e instanceof StreamEvent.BlockDelta bd
                        && bd.delta() instanceof StreamEvent.TextDelta td) {
                    renderer.assistantChunk(td.text());   // 한 글자씩 화면에
                }
            }
            ok = true;
        } catch (RuntimeException ex) {
            renderer.system("요청 실패: " + ex.getMessage());
        }
        renderer.newline();

        Message.AssistantMessage reply = acc.build();
        if (ok && !reply.content().isEmpty()) {
            history.add(reply);                           // 성공·내용 있을 때만 다음 턴 맥락에 포함
        }
    }
}
