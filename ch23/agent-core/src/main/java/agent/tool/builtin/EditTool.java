package agent.tool.builtin;

import agent.message.ContentBlock.ToolResultBlock;
import agent.tool.*;
import agent.tool.schema.Desc;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class EditTool implements Tool<EditTool.Input, EditTool.Result> {

    public record Input(@Desc("편집할 파일 경로") Path filePath,
                        @Desc("찾을 문자열") String oldString,
                        @Desc("바꿀 문자열") String newString,
                        @Desc("모든 매치를 바꿀지(기본 false)") boolean replaceAll) {}

    public record Hunk(int oldStart, int oldLines, int newStart, int newLines, List<String> lines) {}
    public record Result(Path filePath, List<Hunk> hunks, boolean replaceAll) {}

    @Override public String name() { return "Edit"; }
    @Override public String description() { return "파일에서 old_string을 찾아 new_string으로 정확히 치환한다."; }
    @Override public Class<Input> inputType() { return Input.class; }
    @Override public boolean isDestructive(Input in) { return true; }
    // 권한 규칙 매칭은 작업 디렉터리 기준으로 정규화한 절대 경로로(상대 경로 우회 방지).
    // 실행(call)과 같은 식으로 해석해 규칙이 매칭한 경로 == 실제로 편집하는 경로를 보장한다.
    @Override public java.util.Optional<String> permissionSubject(Input in, ToolContext ctx) {
        return java.util.Optional.of(ctx.workingDir().resolve(in.filePath()).normalize().toString());
    }

    @Override
    public ValidationResult validateInput(Input in, ToolContext ctx) {
        if (in.oldString().equals(in.newString()))
            return ValidationResult.fail("old_string과 new_string이 동일합니다.", 1);

        Path p = ctx.workingDir().resolve(in.filePath()).normalize();
        if (Files.isDirectory(p))
            return ValidationResult.fail("디렉터리는 편집할 수 없습니다: " + p, 5);
        boolean exists = Files.exists(p);
        if (!exists) {
            return in.oldString().isEmpty()
                    ? ValidationResult.ok()                              // 신규 파일 생성 허용
                    : ValidationResult.fail("파일이 없습니다: " + p, 7);
        }
        var state = ctx.fileState().get(p);
        if (state.isEmpty())
            return ValidationResult.fail("이 파일은 아직 읽지 않았습니다. 먼저 Read 하세요: " + p, 2);
        try {
            if (FileStateCache.changedSinceRead(p, state.get()))        // staleness(내용 fallback 포함)
                return ValidationResult.fail("파일이 읽은 이후 변경되었습니다. 다시 Read 하세요: " + p, 3);

            String content = normalizeEol(Files.readString(p, StandardCharsets.UTF_8));
            int matches = countMatches(content, in.oldString());
            if (matches == 0) return ValidationResult.fail("old_string을 찾을 수 없습니다.", 8);
            if (matches > 1 && !in.replaceAll())
                return ValidationResult.fail("old_string이 " + matches + "곳에서 매치됩니다. 더 구체적으로 쓰거나 replaceAll을 켜세요.", 9);
        } catch (IOException e) {
            return ValidationResult.fail("파일 읽기 실패: " + e.getMessage(), 4);
        }
        return ValidationResult.ok();
    }

    @Override
    public ToolResult<Result> call(Input in, ToolContext ctx) throws IOException {
        Path path = ctx.workingDir().resolve(in.filePath()).normalize();
        boolean existed = Files.exists(path);

        // 쓰기 직전 staleness 재검사(WriteTool과 동일한 최종 방어선)
        if (existed) {
            var state = ctx.fileState().get(path);
            if (state.isPresent() && FileStateCache.changedSinceRead(path, state.get()))
                throw new IOException("쓰기 직전 파일이 변경되었습니다: " + path);
        }

        String raw = existed ? Files.readString(path, StandardCharsets.UTF_8) : "";
        boolean crlf = majorityCrlf(raw);                              // 다수 줄끝 스타일을 따른다
        String original = normalizeEol(raw);

        String edited = in.replaceAll()
                ? original.replace(in.oldString(), in.newString())
                : replaceFirst(original, in.oldString(), in.newString());
        if (edited.equals(original))                                   // 치환이 일어나지 않음(매치가 사라짐)
            throw new IOException("old_string이 더 이상 존재하지 않습니다(파일이 변경됨). 다시 Read 하세요: " + path);

        List<Hunk> hunks = diff(original, edited);

        if (path.getParent() != null) Files.createDirectories(path.getParent());
        // normalize-then-join: newString에 섞인 \r\n도 한 번 평탄화해 \r\r\n 손상을 막는다
        String toWrite = crlf ? edited.replace("\r\n", "\n").replace("\n", "\r\n") : edited;
        Files.writeString(path, toWrite, StandardCharsets.UTF_8);

        long mtime = Files.getLastModifiedTime(path).toMillis();
        ctx.fileState().set(path, new FileStateCache.FileState(normalizeEol(toWrite), mtime, null, null, false));
        return ToolResult.of(new Result(path, hunks, in.replaceAll()));
    }

    @Override
    public ToolResultBlock mapResult(Result r, String toolUseId) {
        StringBuilder sb = new StringBuilder("편집됨: " + r.filePath() + "\n");
        for (Hunk h : r.hunks()) {
            sb.append("@@ -").append(h.oldStart()).append(",").append(h.oldLines())
              .append(" +").append(h.newStart()).append(",").append(h.newLines()).append(" @@\n");
            for (String line : h.lines()) sb.append(line).append("\n");
        }
        return ToolResultBlock.ok(toolUseId, sb.toString());
    }

    // ── 헬퍼 ─────────────────────────────────────────────
    private static String normalizeEol(String s) { return s.replace("\r\n", "\n").replace("\r", "\n"); }

    /** \r\n이 (\r\n에 속하지 않은) \n보다 많으면 CRLF 파일로 본다(혼합 파일은 다수결). */
    private static boolean majorityCrlf(String s) {
        int crlf = countMatches(s, "\r\n");
        int lf = countMatches(s, "\n") - crlf;
        return crlf > lf;
    }

    private static int countMatches(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) { count++; idx += needle.length(); }
        return count;
    }

    private static String replaceFirst(String s, String oldStr, String newStr) {
        int i = s.indexOf(oldStr);
        return i < 0 ? s : s.substring(0, i) + newStr + s.substring(i + oldStr.length());
    }

    private static List<Hunk> diff(String original, String edited) {
        List<String> a = List.of(original.split("\n", -1));
        List<String> b = List.of(edited.split("\n", -1));
        Patch<String> patch = DiffUtils.diff(a, b);
        List<Hunk> hunks = new ArrayList<>();
        for (AbstractDelta<String> d : patch.getDeltas()) {
            Chunk<String> src = d.getSource(), tgt = d.getTarget();
            List<String> lines = new ArrayList<>();
            for (String l : src.getLines()) lines.add("-" + l);
            for (String l : tgt.getLines()) lines.add("+" + l);
            hunks.add(new Hunk(src.getPosition() + 1, src.size(), tgt.getPosition() + 1, tgt.size(), lines));
        }
        return hunks;
    }
}
