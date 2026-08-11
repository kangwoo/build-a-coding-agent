package agent.cli.config;

import agent.cost.CostTracker.ModelCosts;

import java.math.BigDecimal;
import java.util.Map;

public final class Prices {
    private Prices() {}

    private static ModelCosts mc(String in, String out, String cacheRead) {
        // OpenAI는 cache write 단가가 따로 없다(자동 프리픽스 캐싱) → 0
        return new ModelCosts(new BigDecimal(in), new BigDecimal(out),
                new BigDecimal(cacheRead), BigDecimal.ZERO);
    }

    /** Mtok(백만 토큰)당 USD. (최신 단가로 교체 가능) */
    public static final Map<String, ModelCosts> OPENAI = Map.of(
            "gpt-5.4-mini", mc("0.75", "4.50", "0.075"),   // 기본(저렴)
            "gpt-5.4",      mc("2.50", "15.00", "0.25")     // 상위
    );

    /** 단가표에 없는 모델용 폴백(보수적으로 높게 — 과소청구보다 낫다). */
    public static final ModelCosts FALLBACK = mc("5.00", "15.00", "2.50");
}
