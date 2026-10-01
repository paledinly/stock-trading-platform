package com.sunmo.stockplatform.closing.trajectory;

import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class MinuteFeatureAggregatorTest {
    private ObservedMarketTick tick(String time, long volume, String received) {
        Instant at = Instant.parse("2026-09-30T" + time + "Z");
        var tick = new MarketTick("005930", LocalDate.of(2026, 9, 30), at, BigDecimal.valueOf(100), 10,
                volume, BigDecimal.valueOf(volume * 100), volume, BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                BigDecimal.valueOf(100), BigDecimal.valueOf(120), volume / 2, volume / 2, null, false, null, null);
        return new ObservedMarketTick(tick, null, Instant.parse("2026-09-30T" + received + "Z"));
    }
    @Test void aggregatesOnceRejectsLateTicksAndRequiresWarmup() {
        var a = new MinuteFeatureAggregator(Duration.ofSeconds(2));
        a.accept(tick("05:29:59", 100, "05:29:59"));
        a.accept(tick("05:30:01", 110, "05:30:01"));
        a.accept(tick("05:30:50", 120, "05:30:50"));
        var rows = a.ready(Instant.parse("2026-09-30T05:31:02Z"));
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().complete()).isFalse();
        assertThat(rows.getLast().complete()).isTrue();
        assertThat(rows.getLast().volume()).isEqualByComparingTo("20");
        assertThat(rows.getLast().turnover()).isEqualByComparingTo("2000");
        a.accept(tick("05:30:55", 130, "05:31:03"));
        assertThat(a.rejected()).isEqualTo(1);
        a.acknowledge(rows);
        assertThat(a.ready(Instant.parse("2026-09-30T05:32:00Z"))).isEmpty();
    }
    @Test void lostTradesDoNotProduceCompleteVolumeAndAuctionIsSeparate() {
        var a = new MinuteFeatureAggregator(Duration.ofSeconds(2));
        a.accept(tick("05:29:59", 100, "05:29:59"));
        a.accept(tick("05:30:01", 140, "05:30:01"));
        assertThat(a.ready(Instant.parse("2026-09-30T05:31:02Z")).getLast().complete()).isFalse();
        var auction = new MinuteFeatureAggregator(Duration.ofSeconds(2));
        auction.accept(tick("06:20:00", 200, "06:20:00"));
        assertThat(auction.ready(Instant.parse("2026-09-30T06:21:02Z"))).isEmpty();
    }
}
