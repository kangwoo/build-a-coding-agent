package agent.tool.builtin;

import agent.message.ContentBlock.ToolResultBlock;
import agent.tool.*;
import agent.tool.process.RipGrep;
import agent.tool.schema.Desc;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

public final class GlobTool implements Tool<GlobTool.Input, GlobTool.Result> {

    private static final int LIMIT = 100;

    public record Input(@Desc("파일명 glob 패턴, 예: **/*.java") String pattern,
                        @Desc("검색 시작 경로(기본: 작업 디렉터리)") Optional<Path> path) {}
    public record Result(List<String> files, boolean truncated) {}

    @Override public String name() { return "Glob"; }
    @Override public String description() { return "glob 패턴으로 파일을 찾는다(이름 기준)."; }
    @Override public Class<Input> inputType() { return Input.class; }
    @Override public boolean isReadOnly(Input in) { return true; }
    @Override public boolean isConcurrencySafe(Input in) { return true; }

    @Override
    public ToolResult<Result> call(Input in, ToolContext ctx) throws Exception {
        Path base = in.path().map(p -> ctx.workingDir().resolve(p)).orElse(ctx.workingDir());
        var args = List.of("--files", "--glob", in.pattern(), base.toString());

        RipGrep.Result rg = RipGrep.run(ctx.workingDir(), Duration.ofSeconds(20), args);

        List<String> rel = rg.lines().stream()
                .map(line -> relativize(line, ctx.workingDir()))
                .limit(LIMIT)
                .toList();
        return ToolResult.of(new Result(rel, rg.lines().size() > LIMIT));
    }

    /** 절대경로면 작업 디렉터리 기준 상대경로로. 경로가 아니면 원본 유지(GrepTool과 같은 방어). */
    private static String relativize(String line, Path base) {
        try {
            Path p = Path.of(line);
            if (p.isAbsolute()) return base.relativize(p).toString();
        } catch (RuntimeException ignored) { /* 경로가 아니면 원본 */ }
        return line;
    }

    @Override
    public ToolResultBlock mapResult(Result r, String toolUseId) {
        if (r.files().isEmpty()) return ToolResultBlock.ok(toolUseId, "(매치되는 파일 없음)");
        String body = String.join("\n", r.files());
        if (r.truncated()) body += "\n… (" + LIMIT + "개로 잘림)";
        return ToolResultBlock.ok(toolUseId, body);
    }
}
