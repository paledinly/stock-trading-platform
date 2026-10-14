/*
 * OvernightExecutionSimulator 가 다음 거래일 봉으로 목표가/손절가 가상 청산을 계산하는 규칙을 확인해요.
 * 실제로 체결될 수 없는 가격을 지어내거나, 비용을 빼지 않은 수익을 순수익처럼 보이게 하면 성과가 부풀려지므로 중요해요.
 */
package com.sunmo.stockplatform.closing;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.closing.application.OvernightExecutionSimulator;
import com.sunmo.stockplatform.closing.config.TradingCostProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 가상 체결 심판처럼 청산 가격과 사유를 보수적으로 정하는지 확인하는 테스트예요. */
class OvernightExecutionSimulatorTest {
    /**
     * 무엇: 시가부터 손절가 아래로 갭하락하면 손절가가 아닌 시가로 청산하는지 확인해요.
     * 입력: 진입가 100, 목표 +3%, 손절 -2%, 시가 90 인 봉, 비용 0.
     * 기대: 사유 GAP_STOP, 청산 기준가 90, 총수익률 -10%.
     */
    @Test
    void gapBelowStopExitsAtOpenInsteadOfInventingStopFill() {
        var simulator = simulator(BigDecimal.ZERO);

        var result = simulator.targetOrStop(bd("100"),
                List.of(candle("90", "95", "89", "94")), bd("3"), bd("-2"));

        assertThat(result.exitReason()).isEqualTo("GAP_STOP");
        assertThat(result.exitReferencePrice()).isEqualByComparingTo("90");
        assertThat(result.grossReturnRate()).isEqualByComparingTo("-10.000000");
    }

    /**
     * 무엇: 한 봉에서 목표와 손절이 모두 닿으면 보수적으로 손절로 보고 모호함을 표시하는지 확인해요.
     * 입력: 진입가 100, 고가 104(목표 도달)·저가 97(손절 도달)인 봉, 비용 0.
     * 기대: 사유 AMBIGUOUS_STOP, 총수익률 -2%, 목표·손절 도달과 ambiguous 가 모두 true.
     */
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

    /**
     * 무엇: 비용이 설정되면 총수익률과 별도로 비용 차감 순수익률을 내는지 확인해요.
     * 입력: 각 비용 항목 0.1, 진입가 100, 고가 104 로 목표 +3% 에 닿는 봉.
     * 기대: 총수익률 3%, 순수익률은 그보다 작고 costsApplied 가 true.
     */
    @Test
    void reportsNetReturnSeparatelyWhenCostsAreConfigured() {
        var simulator = simulator(bd("0.1"));

        var result = simulator.targetOrStop(bd("100"),
                List.of(candle("100", "104", "99", "103")), bd("3"), bd("-2"));

        assertThat(result.grossReturnRate()).isEqualByComparingTo("3.000000");
        assertThat(result.netReturnRate()).isLessThan(result.grossReturnRate());
        assertThat(result.costsApplied()).isTrue();
    }

    /** 모든 비용 항목(수수료·세금·스프레드·슬리피지 등)을 같은 값으로 둔 시뮬레이터를 만들어요. */
    private OvernightExecutionSimulator simulator(BigDecimal cost) {
        return new OvernightExecutionSimulator(new TradingCostProperties(cost, cost, cost, cost, cost));
    }

    /** 다음 거래일 09:00(한국시간) 확정 봉을 만드는 도우미예요. */
    private StockCandle candle(String open, String high, String low, String close) {
        return new StockCandle(null, Instant.parse("2026-09-07T00:00:00Z"), bd(open), bd(high), bd(low), bd(close),
                100, bd("10000"), true, 0);
    }

    /** 문자열을 BigDecimal 로 바꾸는 짧은 도우미예요. */
    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
