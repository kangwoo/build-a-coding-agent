package agent.exec;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 계층적 취소 토큰. 부모가 취소되면 모든 자식이 취소된다(부모→자식 전파).
 * reason으로 취소의 종류를 구분하고(interrupt vs 일반 abort), onCancel로 정리 콜백을 단다.
 */
public final class CancellationToken {

    private final CancellationToken parent;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean cancelled;
    private volatile String reason;
    private volatile Runnable detachFromParent;      // child()가 부모에 단 전파 리스너의 해제 핸들

    private CancellationToken(CancellationToken parent) { this.parent = parent; }

    public static CancellationToken root() { return new CancellationToken(null); }
    public static CancellationToken none() { return new CancellationToken(null); }

    /** 자식 토큰. 부모가 취소되면 자식도 취소된다. 다 쓰면 detach()로 부모에서 떼어낸다. */
    public CancellationToken child() {
        CancellationToken c = new CancellationToken(this);
        if (isCancelled()) c.cancel(reason());                         // 이미 취소됐으면 즉시 전파
        else c.detachFromParent = onCancel(() -> c.cancel(reason()));  // 이후 부모 취소 시 전파
        return c;
    }

    /** 부모에 단 전파 리스너를 해제한다 — 안 하면 도구 호출 수만큼 부모에 콜백이 쌓인다(누수). */
    public void detach() {
        Runnable d = detachFromParent;
        if (d != null) d.run();
    }

    public boolean isCancelled() {
        return cancelled || (parent != null && parent.isCancelled());
    }

    public String reason() {
        if (reason != null) return reason;
        return parent != null ? parent.reason() : null;
    }

    public boolean isInterrupt() { return "interrupt".equals(reason()); }

    public void cancel(String reason) {
        synchronized (this) {                            // check-then-act 경쟁 차단
            if (cancelled) return;                       // 멱등
            this.reason = reason;
            this.cancelled = true;
        }
        for (Runnable l : listeners)                      // 정리 콜백(프로세스 kill 등) 실행
            if (listeners.remove(l)) runQuietly(l);       // remove에 성공한 쪽만 — 정확히 1회
    }

    /** 취소 시 실행할 정리 작업 등록(이미 취소됐으면 즉시 실행). 반환된 핸들이 등록을 해제한다. */
    public Runnable onCancel(Runnable listener) {
        listeners.add(listener);                          // 등록이 먼저 — 확인 후 add면 cancel()과 경쟁
        if (isCancelled() && listeners.remove(listener))  // 재확인: 그 사이 취소됐으면
            runQuietly(listener);                         // remove에 성공한 쪽만 실행(중복 방지)
        return () -> listeners.remove(listener);          // 이미 실행·제거됐으면 no-op
    }

    private static void runQuietly(Runnable r) {
        try { r.run(); } catch (RuntimeException ignored) { /* 정리 실패는 삼킨다 */ }
    }
}
