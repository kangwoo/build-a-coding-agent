package agent.skill;

import agent.command.Command;
import agent.command.CommandContext;
import agent.message.ContentBlock;
import agent.message.Json;
import agent.tool.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발견(listing)/실행(call) 분리, 인자 치환, lazy 로드, 예산 절단, MCP 가드,
 * 부분 실패 격리, 슬래시 정규화, ToolExecutor 전 경로까지 검증한다.
 * 모두 in-memory라 실제 API 키가 필요 없다.
 */
class SkillTest {

    @TempDir Path dir;

    // 21장 CommandContext 인터페이스(costSummary/clearConversation/commands)에 의존한다.
    private final CommandContext cc = new CommandContext() {
        public String costSummary() { return ""; }
        public void clearConversation() {}
        public List<Command> commands() { return List.of(); }
    };

    private void writeSkill(String name, String desc, String body) throws Exception {
        Path skill = dir.resolve(name);
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + desc + "\n---\n" + body);
    }

    private SkillRegistry loadOne() throws Exception {
        writeSkill("code-review", "코드 리뷰", "리뷰 대상: $ARGUMENTS");
        return new SkillRegistry(SkillLoader.loadFrom(dir));
    }

    @Test
    void listing_shows_name_and_description_only() throws Exception {
        String listing = loadOne().listing(10_000);
        assertThat(listing).contains("code-review").contains("코드 리뷰");
        assertThat(listing).doesNotContain("리뷰 대상");   // 본문은 목록에 없음
    }

    @Test
    void skill_tool_loads_body_and_substitutes_args() throws Exception {
        var tool = new SkillTool(loadOne(), cc);
        var result = tool.call(new SkillTool.Input("code-review", Optional.of("Main.java")),
                ToolContext.of(dir));
        assertThat(result.data()).contains("리뷰 대상: Main.java");   // 본문 + 인자 치환
    }

    @Test
    void unknown_skill_fails_validation() {
        var tool = new SkillTool(new SkillRegistry(List.of()), cc);
        assertThat(tool.validateInput(new SkillTool.Input("nope", Optional.empty()),
                ToolContext.of(dir)))
                .isInstanceOf(ValidationResult.Fail.class);
    }

    @Test
    void body_is_lazy_loaded_only_on_invocation() {
        // BodyLoader가 호출된 횟수를 센다. 발견(listing)은 본문을 건드리지 않아야 한다.
        AtomicInteger loads = new AtomicInteger();
        var skill = new SkillCommand("s", "설명", dir,
                () -> { loads.incrementAndGet(); return "본문"; }, false);
        var registry = new SkillRegistry(List.of(skill));

        registry.listing(10_000);
        assertThat(loads.get()).as("listing은 본문을 로드하지 않는다").isZero();

        new SkillTool(registry, cc).call(new SkillTool.Input("s", Optional.empty()),
                ToolContext.of(dir));
        assertThat(loads.get()).as("call 시점에 정확히 한 번 로드").isEqualTo(1);
    }

    @Test
    void listing_drops_items_over_budget() throws Exception {
        writeSkill("alpha", "첫 번째 스킬", "본문 a");
        writeSkill("beta", "두 번째 스킬", "본문 b");
        writeSkill("gamma", "세 번째 스킬", "본문 c");
        var registry = new SkillRegistry(SkillLoader.loadFrom(dir));

        // 헤더 + 항목 하나 정도만 들어가는 빠듯한 예산.
        String listing = registry.listing(80);
        long shown = registry.all().stream().map(SkillCommand::name)
                .filter(listing::contains).count();
        assertThat(shown).isPositive().isLessThan(registry.all().size());
    }

    @Test
    void local_skill_substitutes_skill_dir_but_mcp_does_not() {
        Path root = dir.resolve("skill-root");
        var local = new SkillCommand("loc", "d", root, () -> "경로=${AGENT_SKILL_DIR}", false);
        var mcp   = new SkillCommand("rem", "d", root, () -> "경로=${AGENT_SKILL_DIR}", true);

        String localText = text(local.getPrompt("", cc));
        String mcpText   = text(mcp.getPrompt("", cc));

        assertThat(localText).contains("경로=" + root).doesNotContain("${AGENT_SKILL_DIR}");
        assertThat(mcpText).contains("${AGENT_SKILL_DIR}");   // MCP는 치환 금지
    }

    @Test
    void broken_skill_is_skipped_but_others_load() throws Exception {
        writeSkill("good", "정상 스킬", "본문");
        // frontmatter 없는 깨진 스킬.
        Path bad = dir.resolve("bad");
        Files.createDirectories(bad);
        Files.writeString(bad.resolve("SKILL.md"), "frontmatter 없음\n그냥 텍스트");
        // 닫는 --- 없는 깨진 스킬.
        Path bad2 = dir.resolve("bad2");
        Files.createDirectories(bad2);
        Files.writeString(bad2.resolve("SKILL.md"), "---\nname: bad2\n본문만 있고 닫는 구분자 없음");

        var registry = new SkillRegistry(SkillLoader.loadFrom(dir));
        assertThat(registry.find("good")).isPresent();
        assertThat(registry.find("bad")).isEmpty();
        assertThat(registry.find("bad2")).isEmpty();
    }

    @Test
    void leading_slash_is_normalized() throws Exception {
        var tool = new SkillTool(loadOne(), cc);
        var in = new SkillTool.Input("/code-review", Optional.of("X"));
        assertThat(tool.validateInput(in, ToolContext.of(dir))).isInstanceOf(ValidationResult.Ok.class);
        assertThat(tool.call(in, ToolContext.of(dir)).data()).contains("리뷰 대상: X");
    }

    @Test
    void full_path_through_tool_executor_inlines_body() throws Exception {
        var tool = new SkillTool(loadOne(), cc);
        // 모델이 보낸 tool_use를 흉내: 스키마검증→역직렬화(Optional args)→call→mapResult.
        var input = Json.MAPPER.createObjectNode();
        input.put("skill", "code-review");
        input.put("args", "Main.java");
        var use = new ContentBlock.ToolUseBlock("tu-1", "Skill", input);

        var result = ToolExecutor.runToolUse(tool, use, ToolContext.of(dir));

        assertThat(result.isError()).isFalse();
        String text = ((ContentBlock.TextBlock) result.content().get(0)).text();
        assertThat(text).contains("스킬 지침:").contains("리뷰 대상: Main.java");
    }

    private static String text(List<ContentBlock> blocks) {
        return blocks.stream()
                .filter(b -> b instanceof ContentBlock.TextBlock)
                .map(b -> ((ContentBlock.TextBlock) b).text())
                .findFirst().orElse("");
    }
}
