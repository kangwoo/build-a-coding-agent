package agent.command;

import java.util.*;

public final class CommandRegistry {

    private final Map<String, Command> byName = new LinkedHashMap<>();

    public CommandRegistry register(Command c) { byName.put(c.name(), c); return this; }
    public Optional<Command> find(String name) { return Optional.ofNullable(byName.get(name)); }
    public List<Command> all() { return new ArrayList<>(byName.values()); }

    /** 내장 명령(다이렉트) 한 묶음. */
    public static CommandRegistry withBuiltins() {
        var reg = new CommandRegistry();
        reg.register(direct("help", "명령 목록을 표시",
                (args, ctx) -> new DirectResult.Text(formatHelp(ctx.commands()))));
        reg.register(direct("cost", "누적 토큰/비용 표시",
                (args, ctx) -> new DirectResult.Text(ctx.costSummary())));
        reg.register(direct("clear", "대화 기록을 비움",
                (args, ctx) -> { ctx.clearConversation(); return new DirectResult.ClearHistory(); }));
        reg.register(direct("exit", "에이전트를 종료",
                (args, ctx) -> new DirectResult.Exit()));
        reg.register(direct("quit", "/exit와 같음",
                (args, ctx) -> new DirectResult.Exit()));
        return reg;
    }

    private static Command.DirectCommand direct(String name, String desc,
            java.util.function.BiFunction<String, CommandContext, DirectResult> fn) {
        return new Command.DirectCommand() {
            public String name() { return name; }
            public String description() { return desc; }
            public DirectResult call(String args, CommandContext ctx) { return fn.apply(args, ctx); }
        };
    }

    private static String formatHelp(List<Command> commands) {
        StringBuilder sb = new StringBuilder("사용 가능한 명령:\n");
        for (Command c : commands) sb.append("  /").append(c.name())
                .append(" — ").append(c.description()).append("\n");
        return sb.toString().stripTrailing();
    }
}
