package agent.skill;

import agent.command.CommandContext;
import agent.message.ContentBlock;
import agent.message.ContentBlock.ToolResultBlock;
import agent.tool.*;
import agent.tool.schema.Desc;

import java.util.List;
import java.util.Optional;

/**
 * 모델이 호출하는 도구. 발견(목록)은 {@link SkillRegistry#listing}이 맡고, 실행은 여기서:
 * call 시점에 본문을 lazy 로드하고 인자를 치환해 tool_result로 돌려준다.
 *
 * <p>처리된 본문을 별도 user 메시지로 주입하고 tool_result엔 짧은 알림만 두는 방법도 있다.
 * 우리는 단순화를 위해 본문을 tool_result로 직접 돌려, 모델이 다음 턴에 그 지침을 읽게 한다.
 */
public final class SkillTool implements Tool<SkillTool.Input, String> {

    private final SkillRegistry registry;
    private final CommandContext commandContext;

    public SkillTool(SkillRegistry registry, CommandContext commandContext) {
        this.registry = registry;
        this.commandContext = commandContext;
    }

    public record Input(@Desc("호출할 스킬 이름") String skill,
                        @Desc("스킬에 전달할 인자") Optional<String> args) {}

    @Override public String name() { return "Skill"; }

    @Override public String description() {
        return "이름으로 스킬을 호출해 그 절차/지침을 현재 작업에 가져온다.";
    }

    @Override public Class<Input> inputType() { return Input.class; }

    @Override public boolean isReadOnly(Input in) { return true; }   // 지침을 읽어올 뿐

    @Override
    public ValidationResult validateInput(Input in, ToolContext ctx) {
        String name = normalize(in.skill());                        // 선행 슬래시 정규화
        return registry.find(name).isPresent()
                ? ValidationResult.ok()
                : ValidationResult.fail("알 수 없는 스킬: " + in.skill(), 1);
    }

    @Override
    public ToolResult<String> call(Input in, ToolContext ctx) {
        SkillCommand skill = registry.find(normalize(in.skill())).orElseThrow();
        // 발견-실행 분리: 여기서야 본문 로드 + 인자/변수 치환.
        List<ContentBlock> blocks = skill.getPrompt(in.args().orElse(""), commandContext);
        String body = blocks.stream()
                .filter(b -> b instanceof ContentBlock.TextBlock)
                .map(b -> ((ContentBlock.TextBlock) b).text())
                .findFirst().orElse("");
        return ToolResult.of(body);
    }

    @Override
    public ToolResultBlock mapResult(String body, String toolUseId) {
        // inline: 본문을 tool_result로 돌려주면 모델이 다음 턴에 그 지침을 따른다.
        return ToolResultBlock.ok(toolUseId, "스킬 지침:\n\n" + body);
    }

    private static String normalize(String skill) { return skill.replaceFirst("^/", ""); }
}
