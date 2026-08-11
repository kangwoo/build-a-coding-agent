package agent.exec;

import agent.message.ContentBlock.ToolResultBlock;
import agent.message.ContentBlock.ToolUseBlock;
import agent.message.Json;
import agent.tool.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * 한 턴의 여러 tool_use를 "안전한 건 병렬, 아닌 건 직렬"로 분배 실행하되
 * 결과는 호출 순서대로(in-order) 돌려준다. 진행 상황은 결과와 별도 채널(Listener)로 즉시 흘린다.
 */
public final class ToolOrchestrator {

    /** 도구 시작/완료를 즉시 알리는 진행 채널(결과와 별도). */
    public interface Listener {
        void started(ToolUseBlock use);
        void finished(ToolUseBlock use, boolean isError);
    }

    private final ToolRegistry registry;
    private final int maxConcurrency;

    public ToolOrchestrator(ToolRegistry registry) { this(registry, 10); }
    public ToolOrchestrator(ToolRegistry registry, int maxConcurrency) {
        this.registry = registry; this.maxConcurrency = maxConcurrency;
    }

    /** 여러 tool_use를 분배 실행하고, 호출 순서대로 결과를 돌려준다. */
    public List<ToolResultBlock> runAll(List<ToolUseBlock> uses, ToolContext ctx, Listener listener) {
        ToolResultBlock[] results = new ToolResultBlock[uses.size()];   // 위치=순서
        int i = 0;
        while (i < uses.size()) {
            // 연속된 '안전' 도구를 한 병렬 배치로 모은다
            int j = i;
            while (j < uses.size() && isConcurrencySafe(uses.get(j), ctx)) j++;

            if (j > i) {
                runParallel(uses, i, j, ctx, listener, results);   // [i, j) 병렬
                i = j;
            } else {
                results[i] = runOne(uses.get(i), ctx, listener);   // 위험한 도구 단독 직렬
                i++;
            }
        }
        return Arrays.asList(results);
    }

    private void runParallel(List<ToolUseBlock> uses, int from, int to,
                             ToolContext ctx, Listener listener, ToolResultBlock[] results) {
        Semaphore slots = new Semaphore(maxConcurrency);
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int k = from; k < to; k++) {
                final int idx = k;
                final ToolUseBlock use = uses.get(k);
                futures.add(exec.submit(() -> {
                    slots.acquire();
                    try {
                        results[idx] = runOne(use, ctx, listener);   // 서로 다른 인덱스 → 순서 보존
                    } finally {
                        slots.release();
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) f.get();   // 모두 완료까지 대기(완료 순서 무관)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();    // 대기만 접는다 — 남은 슬롯은 아래서 합성
        } catch (ExecutionException e) {
            // 한 도구의 예외가 배치 전체를 죽이면 안 된다 — 해당 슬롯은 아래서 합성
        }
        // 어느 경로로 나왔든 빈 슬롯은 합성 결과로 채운다 — "모든 tool_use에 tool_result" 불변식
        for (int k = from; k < to; k++) {
            if (results[k] == null) {
                results[k] = ToolResultBlock.error(uses.get(k).id(),
                        "도구 실행이 완료되지 못함(취소 또는 내부 오류): " + uses.get(k).name());
                listener.finished(uses.get(k), true);
            }
        }
    }

    private ToolResultBlock runOne(ToolUseBlock use, ToolContext ctx, Listener listener) {
        listener.started(use);

        // ② 이미 취소됨 → 실행하지 않고 합성 결과(불변식 유지)
        if (ctx.cancel().isCancelled()) {
            listener.finished(use, true);
            return ToolResultBlock.error(use.id(), "취소됨: " + use.name());
        }

        // ① 도구마다 자식 토큰(부모=루트 토큰). 도구는 여기에 정리 콜백을 단다.
        CancellationToken childCancel = ctx.cancel().child();
        ToolContext toolCtx = ctx.withCancel(childCancel);

        ToolResultBlock result;
        try {
            result = registry.find(use.name())
                    .map(tool -> ToolExecutor.runToolUse(tool, use, toolCtx))
                    .orElse(ToolResultBlock.error(use.id(), "알 수 없는 도구: " + use.name()));
        } finally {
            childCancel.detach();   // 끝난 호출의 전파 콜백을 부모에 남기지 않는다(누수 방지)
        }

        listener.finished(use, result.isError());
        return result;
    }

    private boolean isConcurrencySafe(ToolUseBlock use, ToolContext ctx) {
        return registry.find(use.name()).map(tool -> safe(tool, use)).orElse(false);
    }

    private static <I, O> boolean safe(Tool<I, O> tool, ToolUseBlock use) {
        try {
            I input = Json.MAPPER.treeToValue(use.input(), tool.inputType());
            return tool.isConcurrencySafe(input);
        } catch (Exception e) {
            return false;   // 파싱 실패 등은 보수적으로 '안전하지 않음'
        }
    }
}
