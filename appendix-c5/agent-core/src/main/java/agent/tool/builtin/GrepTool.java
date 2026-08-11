package agent.tool.builtin;

import agent.message.ContentBlock.ToolResultBlock;
import agent.tool.*;
import agent.tool.process.RipGrep;
import agent.tool.schema.Desc;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

public final class GrepTool implements Tool<GrepTool.Input, GrepTool.Result> {

    private static final int HEAD_LIMIT = 250;

    public enum OutputMode { CONTENT, FILES_WITH_MATCHES, COUNT }

    public record Input(@Desc("검색할 정규식") String pattern,
                        @Desc("검색 경로(기본: 작업 디렉터리)") Optional<Path> path,
                        @Desc("파일 필터 glob, 예: *.java") Optional<String> glob,
                        @Desc("출력 모드") Optional<OutputMode> outputMode,
                        @Desc("대소문자 무시") boolean ignoreCase) {}
    public record Result(OutputMode mode, List<String> lines, boolean truncated) {}

    @Override public String name() { return "Grep"; }
    @Override public String description() { return "정규식으로 파일 내용을 검색한다(ripgrep 기반)."; }
    @Override public Class<Input> inputType() { return Input.class; }
    @Override public boolean isReadOnly(Input in) { return true; }
    @Override public boolean isConcurrencySafe(Input in) { return true; }

    @Override
    public ToolResult<Result> call(Input in, ToolContext ctx) throws Exception {
        OutputMode mode = in.outputMode().orElse(OutputMode.CONTENT);
        List<String> args = new ArrayList<>();
        switch (mode) {
            case FILES_WITH_MATCHES -> args.add("-l");
            case COUNT -> args.add("-c");
            case CONTENT -> args.add("-n");             // 라인 번호 포함
        }
        if (in.ignoreCase()) args.add("-i");
        in.glob().ifPresent(g -> { args.add("--glob"); args.add(g); });
        args.add("--");                                 // 이후는 옵션이 아니다(- 로 시작하는 패턴 보호)
        args.add(in.pattern());
        args.add(in.path().map(p -> ctx.workingDir().resolve(p).toString())
                .orElse(ctx.workingDir().toString()));

        RipGrep.Result rg = RipGrep.run(ctx.workingDir(), Duration.ofSeconds(20), args);
        if (rg.noMatch()) return ToolResult.of(new Result(mode, List.of(), false));   // 무매치는 정상

        List<String> lines = rg.lines().stream()
                .map(l -> relativize(l, ctx.workingDir()))
                .limit(HEAD_LIMIT)
                .toList();
        return ToolResult.of(new Result(mode, lines, rg.lines().size() > HEAD_LIMIT));
    }

    /** "path:lineno:content" 또는 "path"의 경로 부분을 상대화. */
    private static String relativize(String line, Path base) {
        int colon = line.indexOf(':');
        try {
            if (colon > 0) {
                Path p = Path.of(line.substring(0, colon));
                if (p.isAbsolute()) return base.relativize(p) + line.substring(colon);
            } else {
                Path p = Path.of(line);
                if (p.isAbsolute()) return base.relativize(p).toString();
            }
        } catch (RuntimeException ignored) { /* 경로가 아니면 원본 유지 */ }
        return line;
    }

    @Override
    public ToolResultBlock mapResult(Result r, String toolUseId) {
        if (r.lines().isEmpty()) return ToolResultBlock.ok(toolUseId, "(매치 없음)");
        String body = String.join("\n", r.lines());
        if (r.truncated()) body += "\n… (" + HEAD_LIMIT + "줄로 잘림)";
        return ToolResultBlock.ok(toolUseId, body);
    }
}
