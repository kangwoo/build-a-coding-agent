package agent.context;

import agent.message.Message;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.Optional;

public final class ProjectContext {

    private static final System.Logger LOG = System.getLogger(ProjectContext.class.getName());

    /** AGENT.md + 오늘 날짜를 <system-reminder> user 메시지로 만든다.
     *  날짜가 항상 붙어 지금은 empty가 나오지 않는다 — empty 분기는 "빈 주입 금지" 원칙의 방어선. */
    public static Optional<Message.UserMessage> build(Path cwd) {
        StringBuilder sb = new StringBuilder();
        Path memory = cwd.resolve("AGENT.md");
        if (Files.isRegularFile(memory)) {
            try {
                String content = Files.readString(memory);   // 읽기 성공 후에만 붙인다 — 실패가 반쪽 헤더를 남기지 않게
                sb.append("# Project memory (AGENT.md)\n").append(content).append("\n");
            } catch (Exception e) {
                // 주입 실패가 대화를 막으면 안 된다 — 메모리 없이 진행하되, 침묵 대신 경고는 남긴다.
                LOG.log(System.Logger.Level.WARNING, "AGENT.md 읽기 실패 — 프로젝트 메모리 없이 진행: " + e);
            }
        }
        sb.append("Today's date is ").append(LocalDate.now()).append(".");

        if (sb.isEmpty()) return Optional.empty();   // 정말 줄 내용이 없으면 빈 주입을 만들지 않는다(방어선)
        String reminder = "<system-reminder>\n" + sb + "\n</system-reminder>";
        // injected=true: 대화에 주입된 메시지(사용자가 직접 친 게 아님)
        return Optional.of(new Message.UserMessage(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
                java.util.List.of(new agent.message.ContentBlock.TextBlock(reminder)), true));
    }
}
