package agent.tool;

import agent.exec.CancellationToken;
import agent.tool.builtin.FileStateCache;
import java.nio.file.Path;

/** 도구 실행에 필요한 주변 정보. 이후 장에서 필드가 추가된다. */
public record ToolContext(Path workingDir, CancellationToken cancel, FileStateCache fileState) {
    public static ToolContext of(Path workingDir) {
        return new ToolContext(workingDir, CancellationToken.none(), new FileStateCache());
    }

    /** 취소 토큰만 바꾼 사본(14장 — 도구마다 자식 토큰을 줄 때 쓴다). */
    public ToolContext withCancel(CancellationToken c) {
        return new ToolContext(workingDir(), c, fileState());
    }
}
