package agent.cost;

import agent.message.Usage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CostTrackerTest {

    @Test
    void computes_cost_with_bigdecimal_precision() {
        // gpt-5.4: 입력 $2.50/Mtok, 출력 $15.00/Mtok
        var costs = new CostTracker.ModelCosts(
                new BigDecimal("2.50"), new BigDecimal("15.00"), BigDecimal.ZERO, BigDecimal.ZERO);
        var tracker = new CostTracker(Map.of("gpt-5.4", costs), costs);

        tracker.record("gpt-5.4", new Usage(1_000_000, 0, 0, 0, 0));   // 입력 1M → $2.50
        tracker.record("gpt-5.4", new Usage(0, 1_000_000, 0, 0, 0));   // 출력 1M → $15.00

        assertThat(tracker.totalUsd()).isEqualByComparingTo(new BigDecimal("17.50"));
    }

    @Test
    void unknown_model_uses_fallback_and_flags_inaccuracy() {
        var fallback = new CostTracker.ModelCosts(
                new BigDecimal("5.00"), new BigDecimal("15.00"), BigDecimal.ZERO, BigDecimal.ZERO);
        var tracker = new CostTracker(Map.of(), fallback);   // 빈 단가표

        tracker.record("mystery-model", new Usage(1_000_000, 0, 0, 0, 0));   // 폴백 $5.00

        assertThat(tracker.totalUsd()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(tracker.summary()).contains("부정확");
    }
}
