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

class DailyMovingAverageServiceTest {
    private final StockCandleRepository repository = mock(StockCandleRepository.class);
    private final DailyMovingAverageService service = new DailyMovingAverageService(repository);

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

    @Test
    void reportsNotReadyUntilTwentyDailyCandlesExist() {
        var feature = service.calculate(List.of(candle(Instant.parse("2026-09-01T00:00:00Z"), bd("100"))));

        assertThat(feature.ready()).isFalse();
        assertThat(feature.candleCount()).isEqualTo(1);
        assertThat(feature.ma20()).isNull();
    }

    private StockCandle candle(Instant at, BigDecimal close) {
        return new StockCandle(null, "1D", at, close, close, close, close, 100, bd("100000"), true, 0,
                CandleSource.BACKFILL);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
