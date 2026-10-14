/*
 * MinuteFeatureAggregator 가 실시간 체결 틱을 1분 단위 특징 행으로 모으는 동작을 확인해요.
 * 수신 유예(2초) 이후 도착한 늦은 틱으로 이미 내보낸 분을 고치지 않고, 누락 의심 분은 완전하다고 표시하지 않는지가 중요해요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

/** 분 단위 집계기가 늦거나 빠진 체결을 정직하게 처리하는지 확인하는 테스트예요. */
class MinuteFeatureAggregatorTest {
    /** 체결시각 time, 누적거래량 volume, 수신시각 received 를 가진 틱을 만들어요(매 틱 체결량은 10 으로 고정). */
    private ObservedMarketTick tick(String time, long volume, String received) {
        Instant at = Instant.parse("2026-09-30T" + time + "Z");
        var tick = new MarketTick("005930", LocalDate.of(2026, 9, 30), at, BigDecimal.valueOf(100), 10,
                volume, BigDecimal.valueOf(volume * 100), volume, BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                BigDecimal.valueOf(100), BigDecimal.valueOf(120), volume / 2, volume / 2, null, false, null, null);
        return new ObservedMarketTick(tick, null, Instant.parse("2026-09-30T" + received + "Z"));
    }
    /**
     * 무엇: 첫 분은 기준값이 없어 미완성, 다음 분은 완성으로 집계하고, 유예 뒤 도착한 틱은 거부하는지 확인해요.
     * 입력: 05:29:59, 05:30:01, 05:30:50 틱(누적거래량 100→110→120), 이후 05:31:03 에 도착한 05:30:55 틱.
     * 기대: 두 행 중 05:30 분만 완성(거래량 20, 거래대금 2000), 늦은 틱 거부 1건, 확인(acknowledge) 후엔 다시 나오지 않아요.
     */
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
        // 05:30 분은 05:31:02 에 이미 내보냈으므로, 그 뒤 도착한 틱은 덮어쓰지 않고 거부해야 해요.
        a.accept(tick("05:30:55", 130, "05:31:03"));
        assertThat(a.rejected()).isEqualTo(1);
        a.acknowledge(rows);
        assertThat(a.ready(Instant.parse("2026-09-30T05:32:00Z"))).isEmpty();
    }
    /**
     * 무엇: 누적거래량 증가분과 받은 체결량 합이 맞지 않으면(체결 누락) 완성으로 보지 않고, 종가 단일가 시간은 따로 다루는지 확인해요.
     * 입력: 누적거래량이 100→140 으로 늘었지만 받은 체결은 10 뿐인 분, 그리고 06:20 UTC(한국 15:20) 틱.
     * 기대: 해당 분은 미완성이고, 15:20 단일가 구간 틱으로는 분 행이 나오지 않아요.
     */
    @Test void lostTradesDoNotProduceCompleteVolumeAndAuctionIsSeparate() {
        var a = new MinuteFeatureAggregator(Duration.ofSeconds(2));
        a.accept(tick("05:29:59", 100, "05:29:59"));
        a.accept(tick("05:30:01", 140, "05:30:01"));
        assertThat(a.ready(Instant.parse("2026-09-30T05:31:02Z")).getLast().complete()).isFalse();
        // 종가 단일가(15:20~) 구간은 일반 분 집계와 분리돼야 해요.
        var auction = new MinuteFeatureAggregator(Duration.ofSeconds(2));
        auction.accept(tick("06:20:00", 200, "06:20:00"));
        assertThat(auction.ready(Instant.parse("2026-09-30T06:21:02Z"))).isEmpty();
    }
}
