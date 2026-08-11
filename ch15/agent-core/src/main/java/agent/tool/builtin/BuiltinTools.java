package agent.tool.builtin;

import agent.tool.ToolRegistry;

/** 7~11장에서 만든 내장 도구를 한데 등록하는 작은 팩토리. */
public final class BuiltinTools {
    private BuiltinTools() {}

    /** Read/Write/Edit/Glob/Grep/WebFetch/Bash를 등록한 레지스트리. */
    public static ToolRegistry registry() {
        return new ToolRegistry()
                .register(new ReadTool())
                .register(new WriteTool())
                .register(new EditTool())
                .register(new GlobTool())
                .register(new GrepTool())
                .register(new WebFetchTool())
                .register(new BashTool());      // 15장 — 셸 실행
    }
}
