package agent.skill;

import agent.command.Arguments;
import agent.command.Command;
import agent.command.CommandContext;
import agent.message.ContentBlock;

import java.nio.file.Path;
import java.util.List;

/**
 * 스킬은 21장 {@link Command.PromptCommand}의 한 형태다. 별도 위계가 아니라
 * 그 인터페이스의 구현체로, 본문을 호출 시점에만 읽는다(lazy).
 */
public record SkillCommand(String name, String description, Path skillRoot,
                           BodyLoader bodyLoader, boolean fromMcp) implements Command.PromptCommand {

    /** 로컬 스킬 본문에서만 치환되는 경로 변수 이름. */
    static final String SKILL_DIR_VAR = "${AGENT_SKILL_DIR}";

    /** 본문은 호출 시점에만 읽는다(lazy). */
    @FunctionalInterface
    public interface BodyLoader { String load(); }

    @Override
    public List<ContentBlock> getPrompt(String args, CommandContext ctx) {
        String body = bodyLoader.load();                          // ← 여기서야 본문 로드

        // (1) ${AGENT_SKILL_DIR}는 로컬 스킬만 치환(MCP 스킬은 보안상 금지).
        //     순서가 중요하다: 변수 치환 → 인자 치환. 반대로 하면 인자에 든
        //     ${AGENT_SKILL_DIR}까지 실제 경로로 확장된다.
        String resolved = fromMcp ? body
                : body.replace(SKILL_DIR_VAR, skillRoot.toString());

        // (2) $ARGUMENTS/$1 치환 — 한 패스 치환이라 인자 안의 텍스트는 다시 치환되지 않는다.
        String withArgs = Arguments.substitute(resolved, args);

        return List.of(new ContentBlock.TextBlock(withArgs));
    }
}
