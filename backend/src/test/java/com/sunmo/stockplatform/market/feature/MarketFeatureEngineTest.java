/*
 * MarketFeatureEngine(실시간 틱으로 VWAP·거래량 배수 같은 시장 특성값을 계산하는 엔진)을 확인해요.
 * 스캐너와 점수 계산이 이 특성값을 그대로 쓰기 때문에 계산이 정확해야 해요.
 */
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

/** 실시간 시장 특성 계산을 점검하는 테스트예요. */
class MarketFeatureEngineTest {
    private final MarketFeatureEngine engine = new MarketFeatureEngine(new RealtimeDiagnostics());

    /**
     * VWAP(거래량 가중 평균가)와 현재가의 거리 비율을 계산하는지 확인해요.
     * 준비: 100원 10주, 110원 5주(누적 거래대금 1550) 틱을 차례로 넣어요.
     * 기대: VWAP 103.333333, 거리 6.451613%예요.
     */
    @Test
    void calculatesVwapAndDistanceFromRealtimeTick() {
        engine.onTick(tick("2026-08-18T00:00:01Z", "100", 10, 10, "1000", 1));

        MarketFeatureSnapshot snapshot = engine.onTick(
                tick("2026-08-18T00:00:02Z", "110", 5, 15, "1550", 2));

        assertThat(snapshot.vwap()).isEqualByComparingTo("103.333333");
        assertThat(snapshot.vwapDistanceRate()).isEqualByComparingTo("6.451613");
    }

    /**
     * 완료된 5분 구간들을 기준으로 거래량·거래대금 배수를 계산하는지 확인해요.
     * 준비: 5분 간격으로 거래량 10·거래대금 1000씩 쌓이는 틱 6개 뒤에, 거래량 30·거래대금 3300이 늘어난 틱을 넣어요.
     * 기대: 거래량 배수 3, 거래대금 배수 3.3이에요.
     */
    @Test
    void calculatesVolumeAndTurnoverRatioFromCompletedBuckets() {
        for (int index = 0; index < 6; index++) {
            long cumulative = (index + 1L) * 10;
            engine.onTick(tick(Instant.parse("2026-08-18T00:00:01Z").plusSeconds(index * 300L).toString(),
                    "100", 10, cumulative, Long.toString(cumulative * 100), index + 1L));
        }

        MarketFeatureSnapshot snapshot = engine.onTick(
                tick("2026-08-18T00:30:01Z", "110", 30, 90, "9300", 7));

        assertThat(snapshot.volumeRatio()).isEqualByComparingTo("3.000000");
        assertThat(snapshot.turnoverRatio()).isEqualByComparingTo("3.300000");
    }

    /**
     * 누적 매수·매도 체결량의 증가분을 추적하고, 같은 순번 틱은 무시하는지 확인해요.
     * 준비: 매수 100→120, 매도 40→45인 두 틱 뒤에 같은 순번(2)의 120원 틱을 넣어요.
     * 기대: 매수 증가 20, 매도 증가 5이고 중복 틱 뒤에도 가격은 101 그대로예요.
     */
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

    /**
     * 당일 고가와 현재가의 거리 비율을 계산하는지 확인해요.
     * 준비: 현재가 108, 당일 고가 120인 틱을 넣어요.
     * 기대: 거리 10%예요.
     */
    @Test
    void calculatesDayHighDistance() {
        MarketFeatureSnapshot snapshot = engine.onTick(
                tick("2026-08-18T00:00:01Z", "108", 10, 10, "1080", 1, "100", "120", "99"));

        assertThat(snapshot.dayHighDistanceRate()).isEqualByComparingTo("10.000000");
    }

    /**
     * 특성 스냅샷이 JSON으로 직렬화되고 버전·종목코드가 들어가는지 확인해요.
     * 준비: 틱 하나로 스냅샷을 만들어 Jackson(JSON 변환 라이브러리)으로 바꿔요.
     * 기대: featureVersion이 market-feature-v2이고 stockCode가 005930이에요.
     */
    @Test
    void serializesFeatureSnapshot() throws Exception {
        MarketFeatureSnapshot snapshot = engine.onTick(
                tick("2026-08-18T00:00:01Z", "100", 10, 10, "1000", 1));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(snapshot);

        assertThat(json).contains("\"featureVersion\":\"market-feature-v2\"");
        assertThat(json).contains("\"stockCode\":\"005930\"");
    }

    /** 시가·고가·저가와 매수·매도량을 기본값으로 둔 틱을 만드는 도우미예요. */
    private MarketTick tick(String instant, String price, long volume, long cumulativeVolume,
                            String cumulativeValue, long sequence) {
        return tick(instant, price, volume, cumulativeVolume, cumulativeValue, sequence, 0, 0);
    }

    /** 누적 매수·매도량을 지정한 틱을 만드는 도우미예요(고가는 현재가로 둬요). */
    private MarketTick tick(String instant, String price, long volume, long cumulativeVolume,
                            String cumulativeValue, long sequence, long buy, long sell) {
        return tick(instant, price, volume, cumulativeVolume, cumulativeValue, sequence, "100", price, "99", buy, sell);
    }

    /** 시가·고가·저가를 지정한 틱을 만드는 도우미예요. */
    private MarketTick tick(String instant, String price, long volume, long cumulativeVolume,
                            String cumulativeValue, long sequence, String open, String high, String low) {
        return tick(instant, price, volume, cumulativeVolume, cumulativeValue, sequence, open, high, low, 0, 0);
    }

    /** 모든 값을 받아 실제 MarketTick을 만드는 도우미예요(MarketTick에는 매도량을 매수량보다 먼저 넘겨요). */
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
