/*
 * CandleBackfillService.aggregate가 KIS 1분봉을 5분봉으로 합치는 규칙을 확인해요.
 * 특히 장 마감 동시호가 구간의 거래 없는 가짜 1분봉이 종가 봉을 오염시키지 않아야 해요.
 */
package com.sunmo.stockplatform.candle;

import com.sunmo.stockplatform.candle.application.CandleBackfillService;
import com.sunmo.stockplatform.kis.candle.MinuteCandle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 1분봉→5분봉 보충 집계를 점검하는 테스트예요. */
class CandleBackfillAggregationTest {
    /**
     * 1분봉 두 개를 5분봉 OHLCV(시가·고가·저가·종가·거래량)로 합치는지 확인해요.
     * 준비: 같은 5분 구간의 1분봉 2개를 만들어요.
     * 기대: 시가 100, 고가 110, 저가 99, 종가 108, 거래량 30이에요.
     */
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

    /**
     * 15:30 종가 체결 1분봉은 15:25 봉이 되고, 15:20~15:29 동시호가 자리표시 봉은 무시되는지 확인해요.
     * 준비: 15:19 정상 1분봉, 거래 0인 15:20~15:29 1분봉 10개, 15:30 체결 1분봉을 만들어요.
     * 기대: 15:15와 15:25 봉 두 개만 생기고, 15:25 봉의 시가·저가·종가는 102, 거래량 100이에요.
     */
    @Test
    void closingAuctionMinuteBecomesTheFifteenTwentyFiveBarWithoutClosingCallPlaceholders() {
        Instant last = Instant.parse("2026-10-14T06:19:00Z"); // 15:19 KST, continuous trading
        List<MinuteCandle> rows = new java.util.ArrayList<>();
        rows.add(minute(last, "100", "101", "99", "100", 5, "500"));
        for (int i = 1; i <= 10; i++) // 15:20..15:29 closing call: flat, no trades
            rows.add(minute(last.plusSeconds(60L * i), "100", "100", "100", "100", 0, "500"));
        rows.add(minute(last.plusSeconds(660), "102", "102", "102", "102", 100, "10700")); // 15:30 print

        // 실행
        var bars = CandleBackfillService.aggregate(rows);

        // 확인
        assertThat(bars).extracting(bar -> bar.startTime().toString())
                .containsExactly("2026-10-14T06:15:00Z", "2026-10-14T06:25:00Z");
        var closing = bars.getLast();
        assertThat(closing.open()).isEqualByComparingTo("102");
        assertThat(closing.low()).isEqualByComparingTo("102");
        assertThat(closing.close()).isEqualByComparingTo("102");
        assertThat(closing.volume()).isEqualTo(100);
    }

    /** 테스트용 1분봉을 만드는 도우미예요. */
    private MinuteCandle minute(Instant at, String open, String high, String low, String close, long volume,
            String value) {
        return new MinuteCandle(at, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), volume, new BigDecimal(value));
    }
}
