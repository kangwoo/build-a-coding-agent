package agent.cost;

import agent.message.Usage;

import java.math.*;
import java.util.*;

/**
 * 모델별 사용량과 총비용을 집계한다. 스레드 안전하지 않다 — record()는 엔진 프로듀서
 * 스레드에서만 불리고, 표시 측 읽기는 스트림을 다 소비한 뒤에 한다(큐 전달이 happens-before).
 */
public final class CostTracker {

    /** Mtok(백만 토큰)당 USD 단가. */
    public record ModelCosts(BigDecimal input, BigDecimal output,
                             BigDecimal cacheRead, BigDecimal cacheWrite) {}

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final Map<String, ModelCosts> priceTable;
    private final ModelCosts fallback;
    private final Map<String, Usage> usageByModel = new HashMap<>();
    private BigDecimal totalUsd = BigDecimal.ZERO;
    private boolean hasUnknownModel = false;

    public CostTracker(Map<String, ModelCosts> priceTable, ModelCosts fallback) {
        this.priceTable = priceTable; this.fallback = fallback;
    }

    public void record(String model, Usage u) {
        usageByModel.merge(model, u, Usage::plus);
        ModelCosts c = priceTable.get(model);
        if (c == null) { c = fallback; hasUnknownModel = true; }
        totalUsd = totalUsd.add(costOf(u, c));
    }

    private BigDecimal costOf(Usage u, ModelCosts c) {
        return perMtok(u.inputTokens(), c.input())
                .add(perMtok(u.outputTokens(), c.output()))
                .add(perMtok(u.cacheReadInputTokens(), c.cacheRead()))
                .add(perMtok(u.cacheCreationInputTokens(), c.cacheWrite()));
    }
    private static BigDecimal perMtok(long tokens, BigDecimal pricePerMtok) {
        return BigDecimal.valueOf(tokens).multiply(pricePerMtok)
                .divide(MILLION, MathContext.DECIMAL64);
    }

    public BigDecimal totalUsd() { return totalUsd; }

    /** /cost 표시용 요약. */
    public String summary() {
        StringBuilder sb = new StringBuilder("총 비용: $")
                .append(totalUsd.setScale(4, RoundingMode.HALF_UP));
        if (hasUnknownModel) sb.append("  (일부 모델 단가 미상 — 부정확)");
        usageByModel.forEach((m, u) ->
                sb.append("\n  ").append(m).append(": in=").append(u.inputTokens())
                  .append(" out=").append(u.outputTokens()));
        return sb.toString();
    }
}
