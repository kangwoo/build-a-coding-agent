package agent.context;

import java.nio.file.*;

public record EnvInfo(Path cwd, boolean isGitRepo, String platform, String osVersion, String model) {

    public static EnvInfo detect(Path cwd, String model) {
        boolean git = Files.isDirectory(cwd.resolve(".git"));
        return new EnvInfo(cwd, git,
                System.getProperty("os.name"), System.getProperty("os.version"), model);
    }

    /** <env> 블록으로 렌더링. */
    public String render() {
        return """
            <env>
            Working directory: %s
            Is a git repo: %s
            Platform: %s
            OS version: %s
            Model: %s
            </env>""".formatted(cwd, isGitRepo, platform, osVersion, model);
    }
}
