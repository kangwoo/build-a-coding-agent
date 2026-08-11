package agent.exec;

/** 최소 취소 토큰. 14장에서 부모-자식 트리로 확장된다. */
public final class CancellationToken {
    private volatile boolean cancelled;
    private volatile String reason;

    public static CancellationToken none() { return new CancellationToken(); }

    public boolean isCancelled() { return cancelled; }
    public String reason() { return reason; }
    public void cancel(String reason) { this.reason = reason; this.cancelled = true; }
}
