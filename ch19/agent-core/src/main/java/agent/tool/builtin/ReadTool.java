package agent.tool.builtin;

import agent.message.ContentBlock;
import agent.message.ContentBlock.ToolResultBlock;
import agent.tool.*;
import agent.tool.schema.Desc;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ReadTool implements Tool<ReadTool.Input, ReadResult> {

    private static final int DEFAULT_LIMIT = 2000;        // 기본 라인 수
    private static final long MAX_BYTES = 256 * 1024;     // limit 미지정 시 파일 크기 상한
    private static final long MAX_IMAGE_BYTES = 5 * 1024 * 1024;   // 이미지 파일 크기 상한(가장 엄격한 API 한도)

    public record Input(
            @Desc("읽을 파일 경로") Path filePath,
            @Desc("시작 라인(1부터)") Optional<Integer> offset,
            @Desc("읽을 라인 수") Optional<Integer> limit) {}

    @Override public String name() { return "Read"; }
    @Override public String description() {
        return "로컬 파일을 라인 번호와 함께 읽는다. 큰 파일은 offset/limit으로 일부만 읽을 수 있다.";
    }
    @Override public Class<Input> inputType() { return Input.class; }

    @Override public boolean isReadOnly(Input in) { return true; }
    @Override public boolean isConcurrencySafe(Input in) { return true; }

    @Override
    public ValidationResult validateInput(Input in, ToolContext ctx) {
        // errorCode는 도구가 자유롭게 정하는 코드(7장). 여기선 1=없음, 2=디렉터리, 3=offset, 4=limit.
        if (in.offset().isPresent() && in.offset().get() < 1)
            return ValidationResult.fail("offset은 1 이상이어야 합니다.", 3);
        if (in.limit().isPresent() && in.limit().get() < 1)
            return ValidationResult.fail("limit은 1 이상이어야 합니다.", 4);
        Path p = resolve(in, ctx);
        if (!Files.exists(p)) return ValidationResult.fail("파일이 존재하지 않습니다: " + p, 1);
        if (Files.isDirectory(p)) return ValidationResult.fail("디렉터리입니다(파일 경로 필요): " + p, 2);
        return ValidationResult.ok();
    }

    @Override
    public ToolResult<ReadResult> call(Input in, ToolContext ctx) throws IOException {
        Path path = resolve(in, ctx);
        String ext = extension(path);

        if (isImage(ext)) {
            if (Files.size(path) > MAX_IMAGE_BYTES) {   // 범위 읽기가 없으니 무조건 제한
                throw new IOException("이미지가 큽니다(" + Files.size(path)
                        + "B, 상한 " + MAX_IMAGE_BYTES + "B). 줄여서 다시 읽으세요.");
            }
            byte[] bytes = Files.readAllBytes(path);
            String b64 = Base64.getEncoder().encodeToString(bytes);   // 줄바꿈 없는 표준 base64
            return ToolResult.of(new ReadResult.ImageResult(path, imageMediaType(ext), b64));
        }
        // (노트북·PDF도 여기서 분기 — 텍스트 추출 후 NotebookResult/PdfResult. 지면 생략)

        long mtime = Files.getLastModifiedTime(path).toMillis();   // ms 단위(floor)
        int offset = in.offset().orElse(1);
        int limit = in.limit().orElse(DEFAULT_LIMIT);

        if (in.limit().isEmpty() && Files.size(path) > MAX_BYTES) {   // 전체 읽기일 때만 크기 제한
            throw new IOException("파일이 큽니다(" + Files.size(path)
                    + "B). offset/limit으로 범위를 지정해 읽으세요.");
        }

        // ⑥ 중복 회피: 전체를 같은 mtime·같은 범위로 이미 읽었으면 '변경 없음'(부분 뷰는 제외).
        //   mtime이 같으면 내용도 같다고 본다(내용 자체는 비교하지 않는다).
        Optional<FileStateCache.FileState> cached = ctx.fileState().get(path);
        if (cached.isPresent() && !cached.get().partialView()
                && cached.get().timestampMs() == mtime
                && Objects.equals(cached.get().offset(), offset)
                && Objects.equals(cached.get().limit(), limit)) {
            return ToolResult.of(new ReadResult.FileUnchanged(path));
        }

        List<String> all;
        try {
            all = Files.readAllLines(path);   // UTF-8. 대용량은 BufferedReader 스트리밍으로(생략)
        } catch (java.nio.charset.MalformedInputException e) {
            throw new IOException("UTF-8 텍스트가 아닙니다(바이너리로 보임): " + path);
        }
        int total = all.size();
        int start = Math.max(1, offset);                              // 1-based, 음수/0 방어
        int end = Math.min(total, start - 1 + Math.max(0, limit));    // start-1=0-based 시작, +limit=exclusive end
        List<String> slice = start <= total ? all.subList(start - 1, end) : List.of();
        String content = String.join("\n", slice);
        boolean partial = start > 1 || end < total;

        ctx.fileState().set(path, new FileStateCache.FileState(content, mtime, offset, limit, partial));
        return ToolResult.of(new ReadResult.TextResult(path, content, slice.size(), start, total));
    }

    @Override
    public ToolResultBlock mapResult(ReadResult out, String toolUseId) {
        return switch (out) {
            case ReadResult.TextResult t -> {
                if (t.content().isEmpty()) {                       // 빈 결과는 이유를 알려야 모델이 오판 안 한다
                    yield ToolResultBlock.ok(toolUseId, t.totalLines() == 0
                            ? "(빈 파일: " + t.filePath() + ")"
                            : "(offset " + t.startLine() + "이 전체 " + t.totalLines() + "줄보다 큽니다)");
                }
                yield ToolResultBlock.ok(toolUseId, addLineNumbers(t.content(), t.startLine()));
            }
            case ReadResult.FileUnchanged fu ->
                ToolResultBlock.ok(toolUseId, "(직전 읽기 이후 변경되지 않음: " + fu.filePath() + ")");
            case ReadResult.ImageResult img ->
                new ToolResultBlock(toolUseId,
                        List.of(new ContentBlock.ImageBlock(img.mediaType(), img.dataBase64())), false);
            case ReadResult.NotebookResult n -> ToolResultBlock.ok(toolUseId, n.content());
            case ReadResult.PdfResult p -> ToolResultBlock.ok(toolUseId, p.content());
        };
    }

    // ── 헬퍼 ─────────────────────────────────────────────
    private Path resolve(Input in, ToolContext ctx) {
        return ctx.workingDir().resolve(in.filePath()).normalize();
    }

    /** cat -n 형식: 우측 정렬 라인번호 + 탭 + 내용. */
    static String addLineNumbers(String content, int startLine) {
        if (content.isEmpty()) return "";
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format("%6d\t%s", startLine + i, lines[i]));
            if (i < lines.length - 1) sb.append('\n');
        }
        return sb.toString();
    }

    private static String extension(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase();
    }
    private static boolean isImage(String ext) {
        return Set.of("png", "jpg", "jpeg", "gif", "webp").contains(ext);
    }
    /** 확장자 → 표준 미디어타입. jpg는 image/jpeg로 정규화한다(API는 image/jpg를 거부). */
    private static String imageMediaType(String ext) {
        return "image/" + (ext.equals("jpg") ? "jpeg" : ext);
    }
}
