package com.sunmo.stockplatform.closing;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.closing.application.OvernightExecutionSimulator;
import com.sunmo.stockplatform.closing.config.TradingCostProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OvernightExecutionSimulatorTest {
    @Test
    void gapBelowStopExitsAtOpenInsteadOfInventingStopFill() {
        var simulator = simulator(BigDecimal.ZERO);

        var result = simulator.targetOrStop(bd("100"),
                List.of(candle("90", "95", "89", "94")), bd("3"), bd("-2"));

        assertThat(result.exitReason()).isEqualTo("GAP_STOP");
        assertThat(result.exitReferencePrice()).isEqualByComparingTo("90");
        assertThat(result.grossReturnRate()).isEqualByComparingTo("-10.000000");
    }

    @Test
    void sameCandleTargetAndStopUsesConservativeStopAndMarksAmbiguous() {
        var simulator = simulator(BigDecimal.ZERO);

        var result = simulator.targetOrStop(bd("100"),
                List.of(candle("100", "104", "97", "101")), bd("3"), bd("-2"));

        assertThat(result.exitReason()).isEqualTo("AMBIGUOUS_STOP");
        assertThat(result.grossReturnRate()).isEqualByComparingTo("-2.000000");
        assertThat(result.targetHit()).isTrue();
        assertThat(result.stopHit()).isTrue();
        assertThat(result.ambiguous()).isTrue();
    }

    @Test
    void reportsNetReturnSeparatelyWhenCostsAreConfigured() {
        var simulator = simulator(bd("0.1"));

        var result = simulator.targetOrStop(bd("100"),
                List.of(candle("100", "104", "99", "103")), bd("3"), bd("-2"));

        assertThat(result.grossReturnRate()).isEqualByComparingTo("3.000000");
        assertThat(result.netReturnRate()).isLessThan(result.grossReturnRate());
        assertThat(result.costsApplied()).isTrue();
    }

    private OvernightExecutionSimulator simulator(BigDecimal cost) {
        return new OvernightExecutionSimulator(new TradingCostProperties(cost, cost, cost, cost, cost));
    }

    private StockCandle candle(String open, String high, String low, String close) {
        return new StockCandle(null, Instant.parse("2026-09-07T00:00:00Z"), bd(open), bd(high), bd(low), bd(close),
                100, bd("10000"), true, 0);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
