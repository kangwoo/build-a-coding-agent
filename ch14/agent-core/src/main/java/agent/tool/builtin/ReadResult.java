package agent.tool.builtin;

import java.nio.file.Path;

public sealed interface ReadResult
        permits ReadResult.TextResult, ReadResult.ImageResult,
                ReadResult.NotebookResult, ReadResult.PdfResult, ReadResult.FileUnchanged {

    record TextResult(Path filePath, String content, int numLines, int startLine, int totalLines)
            implements ReadResult {}
    record ImageResult(Path filePath, String mediaType, String dataBase64) implements ReadResult {}
    record NotebookResult(Path filePath, String content) implements ReadResult {}
    record PdfResult(Path filePath, String content) implements ReadResult {}
    record FileUnchanged(Path filePath) implements ReadResult {}
}
