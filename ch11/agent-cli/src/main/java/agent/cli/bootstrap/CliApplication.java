package agent.cli.bootstrap;

import agent.cli.repl.Repl;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name = "agent",
    mixinStandardHelpOptions = true,                 // --help, --version 자동
    version = "agent 0.1.0",
    description = "터미널 코딩 에이전트"
)
public final class CliApplication implements Callable<Integer> {

    @Option(names = "--provider", defaultValue = "openai",
            description = "LLM 백엔드 (openai|anthropic|gemini). 24장부터 여러 개가 의미를 갖는다.")
    String provider;

    @Parameters(arity = "0..*",
            description = "비대화형 1회용 프롬프트. 주면 한 번 처리하고 끝낸다(여러 단어는 공백으로 이어 붙인다).")
    List<String> prompt;

    @Override
    public Integer call() throws Exception {
        Bootstrap.init();                 // ② 전역 1회 준비
        var session = Bootstrap.setup();  // ③ 세션 환경 구성
        var repl = new Repl(session, provider);       // provider로 LlmClient 선택
        if (prompt != null && !prompt.isEmpty()) {
            repl.runOnce(String.join(" ", prompt));   // ④' 비대화형 1회 — 한 번 처리하고 끝
        } else {
            repl.run();                               // ④ 무대 가동
        }
        return 0;
    }
}
