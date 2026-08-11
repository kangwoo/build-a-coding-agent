package agent.cli.repl;

import agent.cli.bootstrap.LlmClients;
import agent.cli.bootstrap.Session;
import agent.cli.render.Renderer;
import agent.engine.AgentEvent;
import agent.engine.AgentServices;
import agent.engine.AgentEngine;
import agent.engine.Transition;
import agent.exec.CancellationToken;
import agent.llm.LlmClient;
import agent.llm.StreamCancelledException;
import agent.tool.builtin.BuiltinTools;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;

public final class Repl {
    private static final String SYSTEM_PROMPT =
            "You are a terminal coding agent. Be concise and helpful.";   // 18장에서 본격 조립
    private static final String DEFAULT_MODEL = "gpt-5.4-mini";           // 상위 모델은 "gpt-5.4" (최신 id로 교체 가능)

    private final Session session;
    private final AgentEngine engine;     // 대화 상태를 소유한다(이제 history는 엔진 안에 있다)
    private String oneShot;   // null이 아니면 비대화형 1회 실행 — run()이 READ 루프 대신 이 프롬프트 하나만 처리한다

    public Repl(Session session, String provider) {
        this.session = session;
        LlmClient llm = LlmClients.forProvider(provider);
        this.engine = new AgentEngine(llm, DEFAULT_MODEL,
                BuiltinTools.registry(), SYSTEM_PROMPT, AgentServices.defaults());   // 7~11장 도구 등록
        // 16·19·20장에서 AgentServices.defaults() 자리에 실제 게이트·압축·비용·영속성을 채운다.
    }

    public void run() throws IOException {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            Renderer renderer = new Renderer(terminal);
            if (oneShot != null) {                       // 비대화형 1회 — 배너·READ 루프 없이 한 번 처리하고 끝
                converse(renderer, terminal, oneShot);
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

                converse(renderer, terminal, text);                  // ← 엔진 루프를 돌린다(Ctrl+C 연결)
            }
            renderer.goodbye();
        }
    }

    /** 비대화형 1회 실행: run()과 같은 조립을 거쳐 프롬프트 하나만 처리하고 바로 끝낸다. */
    public void runOnce(String prompt) throws IOException {
        this.oneShot = prompt;
        run();
    }

    /** 사용자 입력 한 건을 엔진에 넘기고, 흘러나오는 이벤트를 화면에 그린다. 턴 동안 Ctrl+C를 취소로 연결. */
    private void converse(Renderer renderer, Terminal terminal, String input) {
        CancellationToken cancel = CancellationToken.root();

        // 이 턴 동안 Ctrl+C → interrupt 취소
        Terminal.SignalHandler prev = terminal.handle(Terminal.Signal.INT,
                sig -> {
                    cancel.cancel("interrupt");
                    renderer.system("⏹ 중단 중…");
                });
        try {
            try (var events = engine.submit(input, cancel)) {
                for (AgentEvent e : events) {
                    switch (e) {
                        case AgentEvent.AssistantTextDelta d -> renderer.assistantChunk(d.text());
                        case AgentEvent.ToolStarted t -> renderer.toolStarted(t.name());
                        case AgentEvent.ToolFinished t -> renderer.toolFinished(t.name(), t.isError());
                        case AgentEvent.TurnFinished tf -> renderTransition(renderer, tf.transition());
                    }
                }
            }
        } catch (StreamCancelledException e) {
            // 소비(이 스레드)가 인터럽트로 끊긴 경우. 예외로 이미 전달된 인터럽트를 플래그로도
            // 남기면 다음 readLine까지 튕기므로 걷어내고, 턴만 접는다.
            Thread.interrupted();
            renderer.system("응답 수신이 중단되었습니다.");
        } catch (IllegalStateException e) {
            // submit() 재진입 가드(12장): 이전 턴 생산자가 close()의 join 상한(5초, 6장)을 넘겨
            // 살아 있으면 다음 입력이 여기로 온다. 잡지 않으면 REPL 프로세스가 통째로 죽는다.
            // 위의 catch가 먼저다 — StreamCancelledException은 이 타입의 하위라, 순서가 바뀌면
            // 취소가 계약 위반으로 잡힌다.
            renderer.system("이전 턴이 아직 정리되지 않았습니다: " + e.getMessage());
        } finally {
            terminal.handle(Terminal.Signal.INT, prev);   // 핸들러 원복
            renderer.newline();
        }
    }

    /** 종료 사유를 화면에 드러낸다(Completed는 조용히, 나머지는 알림 한 줄). */
    private void renderTransition(Renderer renderer, Transition t) {
        switch (t) {
            case Transition.Completed c -> { /* 정상 — 본문 텍스트로 충분 */ }
            case Transition.MaxTurns m -> renderer.system("최대 턴(" + m.turns() + ") 초과로 멈췄습니다.");
            case Transition.ModelError e -> renderer.system("모델 오류: " + e.message());
            case Transition.Aborted a -> renderer.system("취소됨: " + a.reason());
        }
    }
}
