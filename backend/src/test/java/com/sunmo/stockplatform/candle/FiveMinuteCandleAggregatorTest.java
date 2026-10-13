package com.sunmo.stockplatform.candle;

import com.sunmo.stockplatform.candle.application.FiveMinuteCandleAggregator;
import com.sunmo.stockplatform.market.domain.MarketTick;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class FiveMinuteCandleAggregatorTest {
    private final FiveMinuteCandleAggregator aggregator = new FiveMinuteCandleAggregator(Duration.ofSeconds(2));

    @Test
    void aggregatesOhlcvAndClosesAtNextBoundary() {
        var first = tick("2026-08-18T00:00:01Z", "100", 5, 5, "500", 1);
        var second = tick("2026-08-18T00:04:59Z", "110", 3, 8, "830", 2);
        var next = tick("2026-08-18T00:05:00Z", "105", 2, 10, "1040", 3);
        aggregator.accept(first);
        var update = aggregator.accept(second).getFirst();
        assertThat(update.open()).isEqualByComparingTo("100");
        assertThat(update.high()).isEqualByComparingTo("110");
        assertThat(update.volume()).isEqualTo(8);
        var boundary = aggregator.accept(next);
        assertThat(boundary.getFirst().finalCandle()).isTrue();
        assertThat(boundary.getFirst().close()).isEqualByComparingTo("110");
        assertThat(boundary.get(1).startTime()).isEqualTo(Instant.parse("2026-08-18T00:05:00Z"));
    }

    @Test
    void ignoresDuplicateSequence() {
        aggregator.accept(tick("2026-08-18T00:00:01Z", "100", 5, 5, "500", 7));
        assertThat(aggregator.accept(tick("2026-08-18T00:00:02Z", "120", 5, 10, "1100", 7))).isEmpty();
    }

    @Test
    void usesTradeVolumeWhenCumulativeVolumeResets() {
        aggregator.accept(tick("2026-08-18T00:00:01Z", "100", 5, 100, "10000", 1));
        var reset = aggregator.accept(tick("2026-08-18T00:00:02Z", "101", 3, 2, "202", 2)).getFirst();
        assertThat(reset.volume()).isEqualTo(8);
    }

    @Test
    void ignoresOutOfOrderTickWithoutCorruptingFollowingCumulativeDelta() {
        aggregator.accept(tick("2026-08-18T00:00:02Z", "100", 5, 100, "10000", 1));
        assertThat(aggregator.accept(tick("2026-08-18T00:00:01Z", "90", 3, 90, "9000", 2)))
                .isEmpty();

        var next = aggregator.accept(tick("2026-08-18T00:00:03Z", "101", 2, 102, "10202", 3)).getFirst();

        assertThat(next.volume()).isEqualTo(7);
        assertThat(next.low()).isEqualByComparingTo("100");
    }

    @Test
    void watermarkClosesSilentBucket() {
        aggregator.accept(tick("2026-08-18T00:00:01Z", "100", 1, 1, "100", 1));
        assertThat(aggregator.flush(Instant.parse("2026-08-18T00:05:01Z"))).isEmpty();
        assertThat(aggregator.flush(Instant.parse("2026-08-18T00:05:02Z"))).hasSize(1);
    }

    @Test
    void lateTickAfterWatermarkCannotReplaceTheFinalCandleWithAPartialOne() {
        aggregator.accept(tick("2026-08-18T00:00:01Z", "100", 100, 100, "10000", 1));
        aggregator.accept(tick("2026-08-18T00:04:50Z", "101", 500, 600, "60500", 2));
        assertThat(aggregator.flush(Instant.parse("2026-08-18T00:05:02Z")).getFirst().volume()).isEqualTo(600);

        var ignored = aggregator.accept(tick("2026-08-18T00:04:55Z", "102", 50, 650, "65600", 3));

        assertThat(ignored).isEmpty();
        assertThat(aggregator.flush(Instant.parse("2026-08-18T00:10:00Z"))).isEmpty();
    }

    @Test
    void closingPrintBelongsToTheFinalRegularSessionBucket() {
        aggregator.accept(tick("2026-08-18T06:25:01Z", "100", 5, 5, "500", 1));

        var update = aggregator.accept(tick("2026-08-18T06:30:00Z", "101", 2, 7, "702", 2)).getFirst();

        assertThat(update.startTime()).isEqualTo(Instant.parse("2026-08-18T06:25:00Z"));
        assertThat(update.close()).isEqualByComparingTo("101");
        assertThat(update.volume()).isEqualTo(7);
    }

    @Test
    void randomEndClosingPrintAfterAnEmptyClosingCallBecomesTheFinal1525Bar() {
        // Last continuous trade 15:19:59, no executions during the 15:20-15:30 call, print stamped 15:30:17.
        aggregator.accept(tick("2026-08-18T06:19:59Z", "100", 5, 500, "50000", 1));
        var print = aggregator.accept(tick("2026-08-18T06:30:17Z", "103", 40, 540, "54120", 2));

        assertThat(print).hasSize(2);
        assertThat(print.getFirst().finalCandle()).isTrue();
        assertThat(print.getFirst().startTime()).isEqualTo(Instant.parse("2026-08-18T06:15:00Z"));
        assertThat(print.get(1).startTime()).isEqualTo(Instant.parse("2026-08-18T06:25:00Z"));
        assertThat(print.get(1).close()).isEqualByComparingTo("103");
        assertThat(print.get(1).volume()).isEqualTo(40);
        assertThat(aggregator.flush(Instant.parse("2026-08-18T06:30:30Z"))).isEmpty();
        var closed = aggregator.flush(Instant.parse("2026-08-18T06:31:02Z"));
        assertThat(closed).singleElement().satisfies(bar -> {
            assertThat(bar.finalCandle()).isTrue();
            assertThat(bar.startTime()).isEqualTo(Instant.parse("2026-08-18T06:25:00Z"));
        });
    }

    @Test
    void final1525BucketIsNotFinalizedBeforeTheClosingAuctionWindowEnds() {
        aggregator.accept(tick("2026-08-18T06:25:10Z", "100", 1, 1, "100", 1));
        assertThat(aggregator.flush(Instant.parse("2026-08-18T06:30:05Z"))).isEmpty();

        var print = aggregator.accept(tick("2026-08-18T06:30:29Z", "99", 9, 10, "991", 2));

        assertThat(print).singleElement().satisfies(bar -> {
            assertThat(bar.startTime()).isEqualTo(Instant.parse("2026-08-18T06:25:00Z"));
            assertThat(bar.close()).isEqualByComparingTo("99");
            assertThat(bar.volume()).isEqualTo(10);
        });
        assertThat(aggregator.flush(Instant.parse("2026-08-18T06:31:02Z"))).hasSize(1);
    }

    @Test
    void bucketsClosingAuctionWindowAndRejectsPostCloseTrades() {
        assertThat(FiveMinuteCandleAggregator.bucketStart(Instant.parse("2026-08-18T06:20:00Z")))
                .isEqualTo(Instant.parse("2026-08-18T06:20:00Z"));
        assertThat(FiveMinuteCandleAggregator.bucketStart(Instant.parse("2026-08-18T06:29:59Z")))
                .isEqualTo(Instant.parse("2026-08-18T06:25:00Z"));
        assertThat(FiveMinuteCandleAggregator.bucketStart(Instant.parse("2026-08-18T06:30:00Z")))
                .isEqualTo(Instant.parse("2026-08-18T06:25:00Z"));
        assertThat(FiveMinuteCandleAggregator.bucketStart(Instant.parse("2026-08-18T06:30:30Z")))
                .isEqualTo(Instant.parse("2026-08-18T06:25:00Z"));
        assertThat(FiveMinuteCandleAggregator.bucketStart(Instant.parse("2026-08-18T06:31:00Z")))
                .isEqualTo(Instant.parse("2026-08-18T06:25:00Z"));
        assertThatThrownBy(() -> FiveMinuteCandleAggregator.bucketStart(Instant.parse("2026-08-18T06:31:01Z")))
                .isInstanceOf(IllegalArgumentException.class);
        // The after-hours closing-price session (15:40) is never regular-session data.
        assertThatThrownBy(() -> aggregator.accept(tick("2026-08-18T06:40:00Z", "100", 1, 1, "100", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPreMarketTick() {
        assertThatThrownBy(() -> aggregator.accept(tick("2026-08-17T23:59:59Z", "100", 1, 1, "100", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MarketTick tick(String instant, String price, long volume, long cumulative, String value, long sequence) {
        Instant time = Instant.parse(instant);
        return new MarketTick("005930", time.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(), time, new BigDecimal(price),
                volume, cumulative, new BigDecimal(value), sequence);
    }
}
