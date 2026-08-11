package agent.command;

import java.util.List;

public interface CommandContext {
    String costSummary();          // /cost
    void clearConversation();      // /clear
    List<Command> commands();      // /help
}
