package com.sunmo.stockplatform.market.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.market.feature.application.MarketFeatureEngine;
import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class MarketFeatureEngineTest {
    private final MarketFeatureEngine engine = new MarketFeatureEngine(new RealtimeDiagnostics());

    @Test
    void calculatesVwapAndDistanceFromRealtimeTick() {
        engine.onTick(tick("2026-08-18T00:00:01Z", "100", 10, 10, "1000", 1));

        MarketFeatureSnapshot snapshot = engine.onTick(
                tick("2026-08-18T00:00:02Z", "110", 5, 15, "1550", 2));

        assertThat(snapshot.vwap()).isEqualByComparingTo("103.333333");
        assertThat(snapshot.vwapDistanceRate()).isEqualByComparingTo("6.451613");
    }

    @Test
    void calculatesVolumeAndTurnoverRatioFromCompletedBuckets() {
        engine.onTick(tick("2026-08-18T00:00:01Z", "100", 10, 10, "1000", 1));

        MarketFeatureSnapshot snapshot = engine.onTick(
                tick("2026-08-18T00:05:01Z", "110", 30, 40, "4300", 2));

        assertThat(snapshot.volumeRatio()).isEqualByComparingTo("3.000000");
        assertThat(snapshot.turnoverRatio()).isEqualByComparingTo("3.300000");
    }

    @Test
    void tracksBuySellDeltasAndIgnoresDuplicateSequence() {
        engine.onTick(tick("2026-08-18T00:00:01Z", "100", 10, 10, "1000", 1, 100, 40));

        MarketFeatureSnapshot snapshot = engine.onTick(
                tick("2026-08-18T00:00:02Z", "101", 5, 15, "1505", 2, 120, 45));
        MarketFeatureSnapshot duplicate = engine.onTick(
                tick("2026-08-18T00:00:03Z", "120", 1, 16, "1625", 2, 121, 46));

        assertThat(snapshot.buyVolumeDelta()).isEqualTo(20);
        assertThat(snapshot.sellVolumeDelta()).isEqualTo(5);
        assertThat(duplicate.price()).isEqualByComparingTo("101");
    }

    @Test
    void calculatesDayHighDistance() {
        MarketFeatureSnapshot snapshot = engine.onTick(
                tick("2026-08-18T00:00:01Z", "108", 10, 10, "1080", 1, "100", "120", "99"));

        assertThat(snapshot.dayHighDistanceRate()).isEqualByComparingTo("10.000000");
    }

    @Test
    void serializesFeatureSnapshot() throws Exception {
        MarketFeatureSnapshot snapshot = engine.onTick(
                tick("2026-08-18T00:00:01Z", "100", 10, 10, "1000", 1));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(snapshot);

        assertThat(json).contains("\"featureVersion\":\"market-feature-v1\"");
        assertThat(json).contains("\"stockCode\":\"005930\"");
    }

    private MarketTick tick(String instant, String price, long volume, long cumulativeVolume,
                            String cumulativeValue, long sequence) {
        return tick(instant, price, volume, cumulativeVolume, cumulativeValue, sequence, 0, 0);
    }

    private MarketTick tick(String instant, String price, long volume, long cumulativeVolume,
                            String cumulativeValue, long sequence, long buy, long sell) {
        return tick(instant, price, volume, cumulativeVolume, cumulativeValue, sequence, "100", price, "99", buy, sell);
    }

    private MarketTick tick(String instant, String price, long volume, long cumulativeVolume,
                            String cumulativeValue, long sequence, String open, String high, String low) {
        return tick(instant, price, volume, cumulativeVolume, cumulativeValue, sequence, open, high, low, 0, 0);
    }

    private MarketTick tick(String instant, String price, long volume, long cumulativeVolume,
                            String cumulativeValue, long sequence, String open, String high, String low,
                            long buy, long sell) {
        Instant time = Instant.parse(instant);
        return new MarketTick("005930", time.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(), time,
                new BigDecimal(price), volume, cumulativeVolume, new BigDecimal(cumulativeValue), sequence,
                new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal("125.5"),
                sell, buy, new BigDecimal("62.5"), false, new BigDecimal("0"), new BigDecimal("0"));
    }
}
