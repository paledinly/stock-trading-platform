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

    @Test
    void closingAuctionMinuteBecomesTheFifteenTwentyFiveBarWithoutClosingCallPlaceholders() {
        Instant last = Instant.parse("2026-10-14T06:19:00Z"); // 15:19 KST, continuous trading
        List<MinuteCandle> rows = new java.util.ArrayList<>();
        rows.add(minute(last, "100", "101", "99", "100", 5, "500"));
        for (int i = 1; i <= 10; i++) // 15:20..15:29 closing call: flat, no trades
            rows.add(minute(last.plusSeconds(60L * i), "100", "100", "100", "100", 0, "500"));
        rows.add(minute(last.plusSeconds(660), "102", "102", "102", "102", 100, "10700")); // 15:30 print

        var bars = CandleBackfillService.aggregate(rows);

        assertThat(bars).extracting(bar -> bar.startTime().toString())
                .containsExactly("2026-10-14T06:15:00Z", "2026-10-14T06:25:00Z");
        var closing = bars.getLast();
        assertThat(closing.open()).isEqualByComparingTo("102");
        assertThat(closing.low()).isEqualByComparingTo("102");
        assertThat(closing.close()).isEqualByComparingTo("102");
        assertThat(closing.volume()).isEqualTo(100);
    }

    private MinuteCandle minute(Instant at, String open, String high, String low, String close, long volume,
            String value) {
        return new MinuteCandle(at, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), volume, new BigDecimal(value));
    }
}
