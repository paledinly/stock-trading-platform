/*
 * StockCandle(봉 엔티티)의 revise(수정) 규칙을 확인해요.
 * 확정된 실시간 봉이 같은 리비전의 부분 데이터로 덮어써지지 않아야 기록이 보존돼요.
 */
package com.sunmo.stockplatform.candle;

import com.sunmo.stockplatform.candle.domain.CandleSource;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 봉 수정 규칙을 점검하는 테스트예요. */
class StockCandleRevisionTest {
    /**
     * 확정된 실시간 봉은 같은 리비전의 일부 값으로 교체되지 않는지 확인해요.
     * 준비: 거래량 600·종가 108로 확정된 REALTIME 봉에 거래량 50·종가 105로 revise를 호출해요.
     * 기대: 거래량 600, 종가 108이 그대로예요.
     */
    @Test
    void finalizedRealtimeCandleRejectsSameRevisionPartialReplacement() {
        StockCandle candle = new StockCandle(null, "5M", Instant.parse("2026-09-23T06:00:00Z"),
                bd("100"), bd("110"), bd("99"), bd("108"), 600, bd("62000"), true, 0,
                CandleSource.REALTIME);

        candle.revise(bd("105"), bd("105"), bd("105"), bd("105"), 50, bd("5250"), true, 0);

        assertThat(candle.getVolume()).isEqualTo(600);
        assertThat(candle.getClose()).isEqualByComparingTo("108");
    }

    /** 문자열을 BigDecimal로 바꾸는 도우미예요. */
    private BigDecimal bd(String value) { return new BigDecimal(value); }
}
