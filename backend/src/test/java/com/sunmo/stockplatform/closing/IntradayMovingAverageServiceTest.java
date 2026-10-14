/*
 * IntradayMovingAverageService(장중 5분봉 이동평균 Feature 계산)를 검사하는 테스트예요.
 * MA5/MA20/MA60 값, 골든크로스·정배열·MA20 지지/이탈 판정, 20개 미만일 때 준비 안 됨 처리, 평가 시각에 아직 닫히지 않은 봉 제외를 확인해요.
 * 평가 시각 이후에 끝나는 봉이 섞이면 미래 정보가 들어가므로 중요해요.
 */
package com.sunmo.stockplatform.closing;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.application.IntradayMovingAverageService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 장중 이동평균 Feature가 닫힌 5분봉만으로 정확히 계산되는지 확인하는 테스트 모음이에요.
 */
class IntradayMovingAverageServiceTest {
    // 봉 목록을 직접 넘기는 테스트용이라 저장소는 목(mock, 가짜 객체)이에요.
    private final IntradayMovingAverageService service = new IntradayMovingAverageService(mock(StockCandleRepository.class));

    /**
     * 평가 시각에 아직 닫히지 않은 봉은 이동평균에서 빼는지 확인해요.
     * 준비: 종가 100인 5분봉 20개에, 평가 시각(10:40 KST)에 시작해 아직 진행 중인 종가 10000 봉을 저장소가 함께 돌려주게 해요.
     * 기대: 사용한 봉은 20개이고 MA20은 100이어야 해요(10000 봉이 섞이지 않음).
     */
    @Test
    void excludesCandleThatWasNotClosedAtEvaluationTime() {
        var repository = mock(StockCandleRepository.class);
        Instant at = Instant.parse("2026-09-04T01:40:00Z");
        var series = new ArrayList<>(candles(20, "100"));
        series.add(candle(at, bd("10000")));
        series.forEach(candle -> org.springframework.test.util.ReflectionTestUtils.setField(candle, "updatedAt",
                candle.getStartTime().plusSeconds(300)));
        series.forEach(candle -> org.springframework.test.util.ReflectionTestUtils.setField(candle, "createdAt",
                candle.getStartTime().plusSeconds(300)));
        org.mockito.Mockito.when(repository
                .findTop61ByStockIdAndTimeframeAndStartTimeLessThanEqualAndFinalCandleTrueOrderByStartTimeDesc(
                        1L, "5M", at.minusSeconds(300))).thenReturn(series);
        var feature = new IntradayMovingAverageService(repository).calculate(1L, at);
        assertThat(feature.candleCount()).isEqualTo(20);
        assertThat(feature.ma20()).isEqualByComparingTo("100");
    }

    /**
     * 61개 5분봉으로 MA5/MA20/MA60과 추세 판정을 계산하는지 확인해요.
     * 준비: 종가 100인 봉 56개, 92인 봉 4개, 마지막 140인 봉 1개.
     * 기대: 준비 완료, MA5 101.6, MA20 100.4, MA60 약 100.133, 골든크로스·정배열 true, MA20 지지·이탈 false,
     * MA20 대비 거리 약 39.44%예요.
     */
    @Test
    void calculatesIntradayMovingAverageTrendFeatures() {
        List<StockCandle> candles = new ArrayList<>();
        Instant start = Instant.parse("2026-09-04T00:00:00Z");
        for (int index = 0; index < 56; index++) {
            candles.add(candle(start.plusSeconds(index * 300L), bd("100")));
        }
        candles.add(candle(start.plusSeconds(56 * 300L), bd("92")));
        candles.add(candle(start.plusSeconds(57 * 300L), bd("92")));
        candles.add(candle(start.plusSeconds(58 * 300L), bd("92")));
        candles.add(candle(start.plusSeconds(59 * 300L), bd("92")));
        candles.add(candle(start.plusSeconds(60 * 300L), bd("140")));

        var feature = service.calculate(candles);

        assertThat(feature.ready()).isTrue();
        assertThat(feature.candleCount()).isEqualTo(61);
        assertThat(feature.ma5()).isEqualByComparingTo("101.600000");
        assertThat(feature.ma20()).isEqualByComparingTo("100.400000");
        assertThat(feature.ma60()).isEqualByComparingTo("100.133333");
        assertThat(feature.goldenCross()).isTrue();
        assertThat(feature.bullishAlignment()).isTrue();
        assertThat(feature.ma20Support()).isFalse();
        assertThat(feature.ma20Broken()).isFalse();
        assertThat(feature.ma20DistanceRate()).isEqualByComparingTo("39.442231");
    }

    /**
     * 봉이 20개보다 적으면 준비되지 않음으로 보고하는지 확인해요.
     * 입력: 봉 1개. 기대: ready false, 봉 수 1, MA20 null이에요.
     */
    @Test
    void reportsNotReadyUntilTwentyCandlesExist() {
        var feature = service.calculate(List.of(candle(Instant.parse("2026-09-04T00:00:00Z"), bd("100"))));

        assertThat(feature.ready()).isFalse();
        assertThat(feature.candleCount()).isEqualTo(1);
        assertThat(feature.ma20()).isNull();
    }

    /**
     * 마지막 봉이 MA20 근처에서 지지받는지, 아래로 이탈했는지 판정하는지 확인해요.
     * 입력: 종가 100인 20개 봉의 마지막을 저가 99·종가 101인 봉, 또는 저가 98·종가 99인 봉으로 바꿔요.
     * 기대: 앞의 경우 ma20Support true, 뒤의 경우 ma20Broken true예요.
     */
    @Test
    void detectsMa20SupportAndBreakdown() {
        List<StockCandle> support = candles(20, "100");
        support.set(19, candle(Instant.parse("2026-09-04T01:35:00Z"), "101", "102", "99", "101"));
        assertThat(service.calculate(support).ma20Support()).isTrue();

        List<StockCandle> broken = candles(20, "100");
        broken.set(19, candle(Instant.parse("2026-09-04T01:35:00Z"), "99", "100", "98", "99"));
        assertThat(service.calculate(broken).ma20Broken()).isTrue();
    }

    /** 09:00 KST부터 5분 간격으로 같은 종가의 봉을 count개 만들어요. */
    private List<StockCandle> candles(int count, String close) {
        List<StockCandle> values = new ArrayList<>();
        Instant start = Instant.parse("2026-09-04T00:00:00Z");
        for (int index = 0; index < count; index++) {
            values.add(candle(start.plusSeconds(index * 300L), bd(close)));
        }
        return values;
    }

    /** 시가·고가·저가·종가가 모두 같은 확정 봉을 만들어요. */
    private StockCandle candle(Instant at, BigDecimal close) {
        return candle(at, close.toPlainString(), close.toPlainString(), close.toPlainString(), close.toPlainString());
    }

    /** 시가·고가·저가·종가를 직접 정한 확정 봉을 만들어요. */
    private StockCandle candle(Instant at, String open, String high, String low, String close) {
        return new StockCandle(null, at, bd(open), bd(high), bd(low), bd(close), 100, bd("100000"), true, 0);
    }

    /** 문자열로 BigDecimal을 만드는 짧은 도우미예요. */
    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
