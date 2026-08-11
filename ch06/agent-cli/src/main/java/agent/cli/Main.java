package agent.cli;

import agent.cli.bootstrap.CliApplication;
import picocli.CommandLine;

public final class Main {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new CliApplication())
                // 비즈니스 예외(예: 키 누락)는 스택트레이스 대신 한 줄 메시지로.
                .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                    cmd.getErr().println(ex.getMessage());
                    return 1;
                })
                .execute(args);
        System.exit(exitCode);
    }
}
