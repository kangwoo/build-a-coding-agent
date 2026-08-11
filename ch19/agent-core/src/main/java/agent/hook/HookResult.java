package agent.hook;

import java.util.Optional;

public record HookResult(Outcome outcome, Optional<String> permissionDecision,
                         Optional<String> message, Optional<String> additionalContext) {

    public enum Outcome { SUCCESS, BLOCKING, NON_BLOCKING_ERROR }

    public static HookResult success() {
        return new HookResult(Outcome.SUCCESS, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
