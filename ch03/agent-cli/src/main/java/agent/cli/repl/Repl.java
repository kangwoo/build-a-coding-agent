package agent.cli.repl;

import agent.cli.bootstrap.Session;
import agent.cli.render.Renderer;
import agent.warmup.OpenAiChat;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Repl {
    private final Session session;
    private final OpenAiChat openai;

    /** 멀티턴 대화 기억. 매 호출마다 통째로 모델에 보낸다(모델은 상태가 없다). */
    private final List<Map<String, String>> conversation = new ArrayList<>();

    private String oneShot;   // null이 아니면 비대화형 1회 실행 — run()이 READ 루프 대신 이 프롬프트 하나만 처리한다

    public Repl(Session session) {
        this.session = session;
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY 환경 변수가 필요합니다. (부록 A 참고)");
        }
        this.openai = new OpenAiChat(apiKey);
    }

    public void run() throws IOException {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            Renderer renderer = new Renderer(terminal);
            if (oneShot != null) {                       // 비대화형 1회 — 배너·READ 루프 없이 한 번 처리하고 끝
                String reply = evaluate(oneShot, renderer);
                if (reply != null) renderer.assistant(reply);
                return;
            }
            renderer.banner();

            while (true) {
                String input;
                try {
                    input = reader.readLine("› ");          // READ
                } catch (EndOfFileException eof) {           // Ctrl+D → 종료
                    break;
                } catch (UserInterruptException interrupt) { // Ctrl+C → 현재 줄 버리고 계속
                    continue;
                }

                if (input == null) break;
                String text = input.strip();
                if (text.isEmpty()) continue;
                if (text.equals("/exit") || text.equals("/quit")) break;

                // EVAL — OpenAI에 보내 응답을 받는다(비스트리밍). 6장에서 스트리밍으로 교체.
                String reply = evaluate(text, renderer);
                if (reply != null) {
                    renderer.assistant(reply);              // PRINT
                }
            }
            renderer.goodbye();
        }
    }

    /** 비대화형 1회 실행: run()과 같은 조립을 거쳐 프롬프트 하나만 처리하고 바로 끝낸다. */
    public void runOnce(String prompt) throws IOException {
        this.oneShot = prompt;
        run();
    }

    /**
     * 3장의 EVAL: user 입력을 대화에 추가 → OpenAI 호출 → assistant 응답을 대화에 추가.
     * 실패하면 화면에 알리고 대화에는 손대지 않는다(끊긴 턴이 기억을 오염시키지 않게).
     */
    private String evaluate(String userText, Renderer renderer) {
        conversation.add(message("user", userText));
        try {
            String reply = openai.chat(OpenAiChat.DEFAULT_MODEL, conversation);
            conversation.add(message("assistant", reply));
            return reply;
        } catch (IOException e) {
            conversation.remove(conversation.size() - 1);   // 예외는 chat() 중 났으므로 assistant는 아직 없다 → 맨 끝 = 방금 넣은 user
            renderer.system("요청 실패: " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            conversation.remove(conversation.size() - 1);
            renderer.system("요청이 중단되었습니다.");
            return null;
        }
    }

    private static Map<String, String> message(String role, String content) {
        // 순서를 보존하는 작은 맵(JSON 직렬화 시 role,content 순서가 안정적).
        Map<String, String> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}
