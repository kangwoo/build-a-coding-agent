package agent.hook;

import com.fasterxml.jackson.annotation.*;

/** 훅 1개. 지금은 command형만 구현, prompt/http/agent는 같은 sealed 아래로 확장. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({ @JsonSubTypes.Type(value = HookCommand.Command.class, name = "command") })
public sealed interface HookCommand permits HookCommand.Command {
    record Command(String command, Integer timeout) implements HookCommand {}
}
