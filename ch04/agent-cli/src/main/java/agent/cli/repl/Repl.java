package agent.cli.repl;

import agent.cli.bootstrap.Session;
import agent.cli.render.Renderer;
import agent.message.Message;
import agent.warmup.OpenAiChat;
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
    private final Session session;
    private final OpenAiChat chat;
    private final List<Message> conversation = new ArrayList<>();   // ← Map에서 승격

    private String oneShot;   // null이 아니면 비대화형 1회 실행 — run()이 READ 루프 대신 이 프롬프트 하나만 처리한다

    public Repl(Session session) {
        this.session = session;
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY 환경 변수가 필요합니다. (부록 A 참고)");
        }
        this.chat = new OpenAiChat(apiKey);
    }

    public void run() throws IOException {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            Renderer renderer = new Renderer(terminal);
            if (oneShot != null) {                       // 비대화형 1회 — 배너·READ 루프 없이 한 번 처리하고 끝
                conversation.add(Message.UserMessage.of(oneShot));
                renderer.message(evaluate());            // 실패 알림(NoticeMessage)도 같은 경로로 — 다음 턴이 없어 롤백은 생략
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

                Message.UserMessage userMessage = Message.UserMessage.of(text);
                conversation.add(userMessage);
                renderer.message(userMessage);              // 입력을 색 입혀 다시 그림

                Message reply = evaluate();                  // EVAL
                if (reply instanceof Message.NoticeMessage) {
                    // 호출 실패: 방금 넣은 user를 되돌려 대화 기억이 오염되지 않게(3장 §3.6 불변식).
                    conversation.remove(conversation.size() - 1);
                } else {
                    conversation.add(reply);
                }
                renderer.message(reply);                     // PRINT(성공 응답 또는 실패 알림)
            }
            renderer.goodbye();
        }
    }

    /** 비대화형 1회 실행: run()과 같은 조립을 거쳐 프롬프트 하나만 처리하고 바로 끝낸다. */
    public void runOnce(String prompt) throws IOException {
        this.oneShot = prompt;
        run();
    }

    /** EVAL — 2장 warm-up 클라이언트 호출. 5장에서 `LlmClient`로 교체된다(6장 스트리밍). */
    private Message evaluate() {
        try {
            String answer = chat.chat(OpenAiChat.DEFAULT_MODEL,
                    Wire.toChatMessages(conversation));      // ← 임시 어댑터 한 번 통과
            return Message.AssistantMessage.of(
                    List.of(new agent.message.ContentBlock.TextBlock(answer)),
                    agent.message.Usage.EMPTY,               // warm-up은 usage를 모른다(5장에서)
                    "stop");
        } catch (Exception e) {
            return Message.NoticeMessage.of("호출 실패: " + e.getMessage());
        }
    }
}
