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
                converse(renderer, oneShot);
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

                converse(renderer, text);                            // ← 엔진 루프를 돌린다
            }
            renderer.goodbye();
        }
    }

    /** 비대화형 1회 실행: run()과 같은 조립을 거쳐 프롬프트 하나만 처리하고 바로 끝낸다. */
    public void runOnce(String prompt) throws IOException {
        this.oneShot = prompt;
        run();
    }

    /** 사용자 입력 한 건을 엔진에 넘기고, 흘러나오는 이벤트를 화면에 그린다. */
    private void converse(Renderer renderer, String text) {
        var cancel = CancellationToken.none();   // 14장에서 Ctrl+C와 연결
        try (var events = engine.submit(text, cancel)) {
            for (AgentEvent e : events) {
                switch (e) {
                    case AgentEvent.AssistantTextDelta d -> renderer.assistantChunk(d.text());
                    case AgentEvent.ToolStarted t -> renderer.toolStarted(t.name());
                    case AgentEvent.ToolFinished t -> renderer.toolFinished(t.name(), t.isError());
                    case AgentEvent.TurnFinished tf -> renderTransition(renderer, tf.transition());
                }
            }
        }
        renderer.newline();
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
