package com.sunmo.stockplatform.candle;

import com.sunmo.stockplatform.candle.application.CandleBackfillService;
import com.sunmo.stockplatform.kis.candle.MinuteCandle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandleBackfillAggregationTest {
    @Test
    void aggregatesOneMinuteRowsIntoFiveMinuteOhlcv() {
        Instant start = Instant.parse("2026-09-01T00:00:00Z");
        var rows = List.of(minute(start, "100", "105", "99", "104", 10, "1000"),
                minute(start.plusSeconds(60), "104", "110", "103", "108", 20, "3200"));
        var candle = CandleBackfillService.aggregate(rows).getFirst();
        assertThat(candle.open()).isEqualByComparingTo("100");
        assertThat(candle.high()).isEqualByComparingTo("110");
        assertThat(candle.low()).isEqualByComparingTo("99");
        assertThat(candle.close()).isEqualByComparingTo("108");
        assertThat(candle.volume()).isEqualTo(30);
    }

    private MinuteCandle minute(Instant at, String open, String high, String low, String close, long volume,
            String value) {
        return new MinuteCandle(at, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), volume, new BigDecimal(value));
    }
}
