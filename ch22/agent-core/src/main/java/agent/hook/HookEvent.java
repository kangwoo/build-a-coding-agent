package agent.hook;

/** 훅이 걸리는 생명주기 지점. 실전 에이전트는 수십 개를 두지만 우리는 핵심만. */
public enum HookEvent { PRE_TOOL_USE, POST_TOOL_USE, USER_PROMPT_SUBMIT, STOP, SESSION_START, SESSION_END }
