package agent.tool.builtin;

import agent.message.ContentBlock.ToolResultBlock;
import agent.tool.*;
import agent.tool.schema.Desc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class WriteTool implements Tool<WriteTool.Input, WriteTool.Result> {

    public record Input(@Desc("쓸 파일 경로") Path filePath,
                        @Desc("파일 전체 내용") String content) {}
    public record Result(Path filePath, boolean created, int bytes) {}

    @Override public String name() { return "Write"; }
    @Override public String description() { return "파일 전체를 주어진 내용으로 쓴다(없으면 생성, 있으면 덮어씀)."; }
    @Override public Class<Input> inputType() { return Input.class; }
    @Override public boolean isDestructive(Input in) { return true; }   // 덮어쓰기
    // 권한 규칙 매칭은 작업 디렉터리 기준으로 정규화한 절대 경로로(상대 경로 우회 방지).
    // 실행(call)과 같은 식으로 해석해 규칙이 매칭한 경로 == 실제로 쓰는 경로를 보장한다.
    @Override public java.util.Optional<String> permissionSubject(Input in, ToolContext ctx) {
        return java.util.Optional.of(ctx.workingDir().resolve(in.filePath()).normalize().toString());
    }

    @Override
    public ValidationResult validateInput(Input in, ToolContext ctx) {
        Path p = ctx.workingDir().resolve(in.filePath()).normalize();
        if (Files.isDirectory(p))
            return ValidationResult.fail("디렉터리에는 쓸 수 없습니다: " + p, 5);
        if (Files.exists(p)) {
            var state = ctx.fileState().get(p);
            if (state.isEmpty())                                        // ① Read 선행
                return ValidationResult.fail("이 파일은 아직 읽지 않았습니다. 먼저 Read 하세요: " + p, 2);
            try {
                if (FileStateCache.changedSinceRead(p, state.get()))    // ② staleness(내용 fallback 포함)
                    return ValidationResult.fail("파일이 읽은 이후 외부에서 변경되었습니다. 다시 Read 하세요: " + p, 3);
            } catch (IOException e) {
                return ValidationResult.fail("파일 상태 확인 실패: " + e.getMessage(), 4);
            }
        }
        return ValidationResult.ok();
    }

    @Override
    public ToolResult<Result> call(Input in, ToolContext ctx) throws IOException {
        Path path = ctx.workingDir().resolve(in.filePath()).normalize();
        boolean existed = Files.exists(path);
        String content = in.content().replace("\r\n", "\n").replace("\r", "\n");   // Write는 LF로 통일

        if (path.getParent() != null) Files.createDirectories(path.getParent());

        // ── critical section: 최종 확인과 쓰기 사이에 블로킹 I/O를 넣지 않는다(진짜 원자성은 아님, 10.7) ──
        if (existed) {
            var state = ctx.fileState().get(path);
            if (state.isPresent() && FileStateCache.changedSinceRead(path, state.get()))
                throw new IOException("쓰기 직전 파일이 변경되었습니다: " + path);
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
        // ────────────────────────────────────────────────────────

        long newMtime = Files.getLastModifiedTime(path).toMillis();
        ctx.fileState().set(path, new FileStateCache.FileState(content, newMtime, null, null, false));
        return ToolResult.of(new Result(path, !existed, content.getBytes(StandardCharsets.UTF_8).length));
    }

    @Override
    public ToolResultBlock mapResult(Result r, String toolUseId) {
        return ToolResultBlock.ok(toolUseId,
                (r.created() ? "생성됨" : "갱신됨") + ": " + r.filePath() + " (" + r.bytes() + " bytes)");
    }
}
