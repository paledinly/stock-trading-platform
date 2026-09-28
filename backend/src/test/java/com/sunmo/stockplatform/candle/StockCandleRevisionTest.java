package com.sunmo.stockplatform.candle;

import com.sunmo.stockplatform.candle.domain.CandleSource;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StockCandleRevisionTest {
    @Test
    void finalizedRealtimeCandleRejectsSameRevisionPartialReplacement() {
        StockCandle candle = new StockCandle(null, "5M", Instant.parse("2026-09-23T06:00:00Z"),
                bd("100"), bd("110"), bd("99"), bd("108"), 600, bd("62000"), true, 0,
                CandleSource.REALTIME);

        candle.revise(bd("105"), bd("105"), bd("105"), bd("105"), 50, bd("5250"), true, 0);

        assertThat(candle.getVolume()).isEqualTo(600);
        assertThat(candle.getClose()).isEqualByComparingTo("108");
    }

    private BigDecimal bd(String value) { return new BigDecimal(value); }
}
