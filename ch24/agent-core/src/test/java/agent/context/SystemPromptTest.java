package agent.context;

import agent.message.ContentBlock;
import agent.message.Message;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPromptTest {

    @TempDir Path dir;

    @Test
    void sections_in_order_with_boundary() {
        var builder = new SystemPromptBuilder();
        var sections = builder.build(dir, "test-model");
        // 정체성 → … → 경계 → 환경 순서
        int identity = indexOfContaining(sections, "terminal coding agent");
        int boundary = sections.indexOf(SystemPromptBuilder.DYNAMIC_BOUNDARY);
        int env = indexOfContaining(sections, "<env>");
        assertThat(identity).isLessThan(boundary);
        assertThat(boundary).isLessThan(env);

        // blocks()는 같은 경계를 구조로 나른다 — 마커 없이 정적 앞, 동적 뒤
        var blocks = builder.blocks(dir, "test-model");
        assertThat(blocks).noneMatch(b -> b.text().equals(SystemPromptBuilder.DYNAMIC_BOUNDARY));
        assertThat(blocks.get(0).dynamic()).isFalse();                 // 정적 프리픽스가 앞
        assertThat(blocks.get(blocks.size() - 1).dynamic()).isTrue();  // 동적 꼬리(<env>)가 뒤
        assertThat(blocks.get(blocks.size() - 1).text()).contains("<env>");
    }

    @Test
    void env_info_reports_cwd_and_git() {
        String prompt = new SystemPromptBuilder().render(dir, "m");
        assertThat(prompt).contains("Working directory: " + dir);
        assertThat(prompt).contains("Is a git repo: false");   // 임시 디렉터리엔 .git 없음
    }

    @Test
    void render_has_no_blank_section_from_boundary() {
        // 경계 마커를 통째로 빼므로 빈 줄(연속 개행 3개 이상)이 남지 않아야 한다(캐시 안정성).
        String prompt = new SystemPromptBuilder().render(dir, "m");
        assertThat(prompt).doesNotContain("\n\n\n");
        assertThat(prompt).doesNotContain(SystemPromptBuilder.DYNAMIC_BOUNDARY);
    }

    @Test
    void agent_md_becomes_injected_user_message() throws Exception {
        Files.writeString(dir.resolve("AGENT.md"), "Always use tabs.");
        Message.UserMessage ctx = ProjectContext.build(dir).orElseThrow();

        assertThat(ctx.injected()).isTrue();                   // 주입된 메시지
        String text = ((ContentBlock.TextBlock) ctx.content().get(0)).text();
        assertThat(text).contains("<system-reminder>").contains("Always use tabs.");
    }

    private int indexOfContaining(java.util.List<String> list, String needle) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).contains(needle)) return i;
        return -1;
    }
}
