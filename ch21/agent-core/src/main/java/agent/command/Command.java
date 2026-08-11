package agent.command;

import agent.message.ContentBlock;

import java.util.List;

public sealed interface Command permits Command.DirectCommand, Command.PromptCommand {

    String name();
    String description();

    /** 즉시 실행되는 명령(모델 비경유). */
    non-sealed interface DirectCommand extends Command {
        DirectResult call(String args, CommandContext ctx);
    }

    /** 텍스트로 확장되어 모델에 주입되는 명령(스킬의 토대). */
    non-sealed interface PromptCommand extends Command {
        List<ContentBlock> getPrompt(String args, CommandContext ctx);
    }
}
