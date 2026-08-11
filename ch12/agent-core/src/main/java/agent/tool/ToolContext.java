package agent.tool;

import agent.exec.CancellationToken;
import agent.tool.builtin.FileStateCache;
import java.nio.file.Path;

/** 도구 실행에 필요한 주변 정보. 이후 장에서 필드가 추가된다. */
public record ToolContext(Path workingDir, CancellationToken cancel, FileStateCache fileState) {
    public static ToolContext of(Path workingDir) {
        return new ToolContext(workingDir, CancellationToken.none(), new FileStateCache());
    }
}
