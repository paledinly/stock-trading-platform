/*
 * DailyMovingAverageService 가 저장된 일봉으로 5/20/60일 이동평균과 추세 특징을 계산하는 부분을 확인해요.
 * 탐지 당일 일봉(아직 확정되지 않은 값)을 쓰면 미래 정보가 섞이므로, 탐지일 이전 확정 일봉만 조회하는지가 중요해요.
 */
package com.sunmo.stockplatform.closing;

import com.sunmo.stockplatform.candle.domain.CandleSource;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.application.DailyMovingAverageService;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 일봉 이동평균 계산과 조회 범위가 point-in-time 규칙을 지키는지 확인하는 테스트예요. */
class DailyMovingAverageServiceTest {
    /** 일봉 조회 저장소 목(mock, 가짜 객체)이에요. */
    private final StockCandleRepository repository = mock(StockCandleRepository.class);
    private final DailyMovingAverageService service = new DailyMovingAverageService(repository);

    /**
     * 무엇: 일봉 40개로 이동평균과 추세 판정값을 계산하는지 확인해요.
     * 입력: 종가가 100 부터 하루 1씩 오르는 일봉 40개.
     * 기대: 준비 완료, MA5 137, MA20 129.5, MA60 은 데이터 부족으로 null, 정배열·상승 판정 true, 과열 false.
     */
    @Test
    void calculatesDailyTrendFeaturesFromStoredDailyCandles() {
        List<StockCandle> candles = new ArrayList<>();
        Instant start = Instant.parse("2026-06-01T00:00:00Z");
        for (int index = 0; index < 40; index++) {
            candles.add(candle(start.plusSeconds(index * 86400L), bd(String.valueOf(100 + index))));
        }

        var feature = service.calculate(candles);

        assertThat(feature.ready()).isTrue();
        assertThat(feature.candleCount()).isEqualTo(40);
        assertThat(feature.ma5()).isEqualByComparingTo("137.000000");
        assertThat(feature.ma20()).isEqualByComparingTo("129.500000");
        assertThat(feature.ma60()).isNull();
        assertThat(feature.closeAboveMa20()).isTrue();
        assertThat(feature.ma5AboveMa20()).isTrue();
        assertThat(feature.ma20Rising()).isTrue();
        assertThat(feature.bullishAlignment()).isTrue();
        assertThat(feature.overextendedFromMa20()).isFalse();
    }

    /**
     * 무엇: 탐지 기준으로 일봉을 조회할 때 확정된 일봉 중 탐지일 이전 것만 요청하는지 확인해요.
     * 입력: 2026-09-04 15:20(한국시간) 탐지.
     * 기대: 시작시각 상한이 2026-09-03 00:00(한국시간, UTC 09-02 15:00)인 확정 1D 조회를 해요(전일 일봉까지 포함, 당일 제외).
     */
    @Test
    void queriesOnlyCompletedDailyCandlesBeforeDetectionDate() {
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(7L);
        ScannerDetection detection = mock(ScannerDetection.class);
        when(detection.getStock()).thenReturn(stock);
        when(detection.getDetectedAt()).thenReturn(Instant.parse("2026-09-04T06:20:00Z"));
        when(repository.findTop61ByStockIdAndTimeframeAndStartTimeLessThanEqualAndFinalCandleTrueOrderByStartTimeDesc(
                eq(7L), eq("1D"), any())).thenReturn(List.of());

        service.calculate(detection);

        verify(repository).findTop61ByStockIdAndTimeframeAndStartTimeLessThanEqualAndFinalCandleTrueOrderByStartTimeDesc(
                eq(7L), eq("1D"), eq(Instant.parse("2026-09-02T15:00:00Z")));
    }

    /**
     * 무엇: 일봉이 20개 미만이면 준비되지 않음으로 표시하는지 확인해요.
     * 입력: 일봉 1개.
     * 기대: ready=false, 개수 1, MA20 null.
     */
    @Test
    void reportsNotReadyUntilTwentyDailyCandlesExist() {
        var feature = service.calculate(List.of(candle(Instant.parse("2026-09-01T00:00:00Z"), bd("100"))));

        assertThat(feature.ready()).isFalse();
        assertThat(feature.candleCount()).isEqualTo(1);
        assertThat(feature.ma20()).isNull();
    }

    /** 시가·고가·저가·종가가 모두 같은 확정 일봉(BACKFILL 출처)을 만들어요. */
    private StockCandle candle(Instant at, BigDecimal close) {
        return new StockCandle(null, "1D", at, close, close, close, close, 100, bd("100000"), true, 0,
                CandleSource.BACKFILL);
    }

    /** 문자열을 BigDecimal 로 바꾸는 짧은 도우미예요. */
    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
