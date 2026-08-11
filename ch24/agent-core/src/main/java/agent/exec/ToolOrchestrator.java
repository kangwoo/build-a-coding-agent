package agent.exec;

import agent.hook.HookRunner;
import agent.message.ContentBlock;
import agent.message.ContentBlock.ToolResultBlock;
import agent.message.ContentBlock.ToolUseBlock;
import agent.message.Json;
import agent.permission.PermissionGate;
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
    private final PermissionGate gate;     // 16장 — 엔진이 생성 시 넘긴다(services.gate())
    private final HookRunner hooks;        // 17장 — PreToolUse 훅(services.hooks())
    private final int maxConcurrency;

    public ToolOrchestrator(ToolRegistry registry) {
        this(registry, PermissionGate.allowAll(), HookRunner.none(), 10);
    }
    public ToolOrchestrator(ToolRegistry registry, PermissionGate gate) {
        this(registry, gate, HookRunner.none(), 10);
    }
    public ToolOrchestrator(ToolRegistry registry, PermissionGate gate, HookRunner hooks) {
        this(registry, gate, hooks, 10);
    }
    public ToolOrchestrator(ToolRegistry registry, int maxConcurrency) {
        this(registry, PermissionGate.allowAll(), HookRunner.none(), maxConcurrency);
    }
    public ToolOrchestrator(ToolRegistry registry, PermissionGate gate, HookRunner hooks, int maxConcurrency) {
        this.registry = registry; this.gate = gate; this.hooks = hooks; this.maxConcurrency = maxConcurrency;
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

        // 이미 취소됨 → 실행하지 않고 합성 결과(불변식 유지, 14장)
        if (ctx.cancel().isCancelled()) {
            listener.finished(use, true);
            return ToolResultBlock.error(use.id(), "취소됨: " + use.name());
        }

        // PreToolUse 훅(17장): deny면 즉시 차단. 훅 실행 자체의 예외(잘못된 matcher 정규식 등)는
        // 이 도구의 오류 결과로 국한한다 — 루프 전체를 죽이지 않는다.
        HookRunner.PreToolDecision hook;
        try {
            hook = hooks.runPreToolUse(use.name(), use.input());
        } catch (RuntimeException e) {
            listener.finished(use, true);
            return ToolResultBlock.error(use.id(), "훅 실행 오류: " + e.getMessage());
        }
        if (hook.denied()) {
            listener.finished(use, true);
            return ToolResultBlock.error(use.id(), "훅이 차단함: " + hook.message());
        }

        // 도구마다 자식 토큰(부모=루트 토큰, 14장). 도구는 여기에 정리 콜백을 단다.
        CancellationToken childCancel = ctx.cancel().child();
        ToolContext toolCtx = ctx.withCancel(childCancel);

        // 훅이 통과해도 권한 게이트(16장)는 그대로 적용 — allow가 규칙을 우회 못 함
        ToolResultBlock result;
        try {
            result = registry.find(use.name())
                    .map(tool -> ToolExecutor.runToolUse(tool, use, toolCtx, gate))
                    .orElse(ToolResultBlock.error(use.id(), "알 수 없는 도구: " + use.name()));
        } finally {
            childCancel.detach();   // 끝난 호출의 전파 콜백을 부모에 남기지 않는다(누수 방지, 14장)
        }

        // 훅이 더한 컨텍스트는 결과 뒤에 붙여 모델이 보게 한다.
        if (!hook.addedContext().isEmpty()) {
            result = withAddedContext(result, hook.addedContext());
        }

        listener.finished(use, result.isError());
        return result;
    }

    private static ToolResultBlock withAddedContext(ToolResultBlock result, List<String> added) {
        List<ContentBlock> content = new ArrayList<>(result.content());
        content.add(new ContentBlock.TextBlock(
                "<hook-additional-context>\n" + String.join("\n", added) + "\n</hook-additional-context>"));
        return new ToolResultBlock(result.toolUseId(), content, result.isError());
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
