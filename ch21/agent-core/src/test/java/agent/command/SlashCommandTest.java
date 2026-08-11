package agent.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SlashCommandTest {

    /** 호출 플래그를 들고 다니는 테스트용 CommandContext. */
    private static CommandContext ctx(CommandRegistry reg, String cost, AtomicBoolean cleared) {
        return new CommandContext() {
            public String costSummary() { return cost; }
            public void clearConversation() { cleared.set(true); }
            public List<Command> commands() { return reg.all(); }
        };
    }

    @Test
    void parses_command_and_args() {
        var p = SlashCommands.parse("/cost detail").orElseThrow();
        assertThat(p.name()).isEqualTo("cost");
        assertThat(p.args()).isEqualTo("detail");
    }

    @Test
    void parses_bare_command_with_empty_args() {
        var p = SlashCommands.parse("/help").orElseThrow();
        assertThat(p.name()).isEqualTo("help");
        assertThat(p.args()).isEmpty();
    }

    @Test
    void trims_surrounding_whitespace_before_parsing() {
        var p = SlashCommands.parse("   /cost   ").orElseThrow();
        assertThat(p.name()).isEqualTo("cost");
        assertThat(p.args()).isEmpty();
    }

    @Test
    void file_path_is_not_a_command() {
        assertThat(SlashCommands.parse("/tmp/notes.txt please read")).isEmpty();
        assertThat(SlashCommands.parse("/var/log")).isEmpty();
    }

    @Test
    void plain_text_is_not_a_command() {
        assertThat(SlashCommands.parse("hello there")).isEmpty();
        assertThat(SlashCommands.parse("")).isEmpty();
    }

    @Test
    void registry_finds_builtins_and_preserves_order() {
        var reg = CommandRegistry.withBuiltins();
        assertThat(reg.find("help")).isPresent();
        assertThat(reg.find("cost")).isPresent();
        assertThat(reg.find("clear")).isPresent();
        assertThat(reg.find("nope")).isEmpty();
        assertThat(reg.all()).extracting(Command::name)
                .containsExactly("help", "cost", "clear", "exit", "quit");   // LinkedHashMap 등록 순서 유지

        // /exit·/quit은 둘 다 Exit 신호를 돌려준다 — 실제 종료는 임베더(REPL) 몫.
        var c = ctx(reg, "", new AtomicBoolean());
        var exit = (Command.DirectCommand) reg.find("exit").orElseThrow();
        var quit = (Command.DirectCommand) reg.find("quit").orElseThrow();
        assertThat(exit.call("", c)).isInstanceOf(DirectResult.Exit.class);
        assertThat(quit.call("", c)).isInstanceOf(DirectResult.Exit.class);
    }

    @Test
    void help_lists_commands() {
        var reg = CommandRegistry.withBuiltins();
        var c = ctx(reg, "", new AtomicBoolean());
        var help = (Command.DirectCommand) reg.find("help").orElseThrow();
        var text = ((DirectResult.Text) help.call("", c)).text();
        assertThat(text).contains("/cost").contains("/clear").contains("/help")
                .contains("/exit").contains("/quit");
    }

    @Test
    void clear_command_clears_conversation_and_returns_clearhistory() {
        var reg = CommandRegistry.withBuiltins();
        var cleared = new AtomicBoolean(false);
        var c = ctx(reg, "", cleared);
        var clear = (Command.DirectCommand) reg.find("clear").orElseThrow();
        var result = clear.call("", c);
        assertThat(cleared).isTrue();
        assertThat(result).isInstanceOf(DirectResult.ClearHistory.class);
    }

    @Test
    void cost_command_passes_through_cost_summary() {
        var reg = CommandRegistry.withBuiltins();
        var c = ctx(reg, "총 비용: $1.2345", new AtomicBoolean());
        var cost = (Command.DirectCommand) reg.find("cost").orElseThrow();
        var text = ((DirectResult.Text) cost.call("", c)).text();
        assertThat(text).isEqualTo("총 비용: $1.2345");
    }

    @Test
    void argument_substitution() {
        assertThat(Arguments.substitute("Review $1 for bugs", "Main.java"))
                .isEqualTo("Review Main.java for bugs");
        assertThat(Arguments.substitute("Do the thing", "x"))
                .endsWith("ARGUMENTS: x");   // placeholder 없으면 append
    }

    @Test
    void substitutes_arguments_placeholder() {
        assertThat(Arguments.substitute("$ARGUMENTS!", "a b")).isEqualTo("a b!");
    }

    @Test
    void no_append_when_no_placeholder_and_no_args() {
        assertThat(Arguments.substitute("Do the thing", "")).isEqualTo("Do the thing");
    }

    @Test
    void dollar_and_backslash_in_args_are_literal() {
        // quoteReplacement 덕에 $·\ 가 치환 특수문자로 깨지지 않는다.
        assertThat(Arguments.substitute("echo $1", "a$b")).isEqualTo("echo a$b");
        assertThat(Arguments.substitute("echo $1", "a\\b")).isEqualTo("echo a\\b");
    }

    @Test
    void two_digit_index_is_not_partially_substituted() {
        // $10은 $1로 부분 치환되지 않는다(정규식 경계).
        var out = Arguments.substitute("$1 $10", "first");
        assertThat(out).startsWith("first $10");   // $10은 인자가 없어 원문 유지
    }
}
