package agent.cli.bootstrap;

import java.util.concurrent.atomic.AtomicBoolean;

public final class Bootstrap {
    private Bootstrap() {}

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    /** 전역 1회 준비. 메모이즈된 초기화 — 두 번 호출돼도 한 번만 실행. */
    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) return;
        // 추후: 설정 로딩, 네트워크/프록시, 종료 훅 등록 (16·17장)
    }

    /** 이번 세션 환경 구성. 추후: worktree, 훅 스냅샷, 트랜스크립트 시작 (20장). */
    public static Session setup() {
        return Session.create();
    }
}
