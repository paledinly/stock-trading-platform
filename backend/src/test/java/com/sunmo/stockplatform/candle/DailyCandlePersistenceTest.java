package com.sunmo.stockplatform.candle;

import com.sunmo.stockplatform.candle.application.DailyCandlePersistence;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.kis.candle.DailyCandle;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DailyCandlePersistenceTest {
    @Test
    void savesOnlyMissingFinalRawDailyCandlesBeforeRecommendationDate() {
        StockCandleRepository repository = mock(StockCandleRepository.class);
        DailyCandlePersistence service = new DailyCandlePersistence(repository);
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(1L);
        when(repository.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("1D"), any(), any())).thenReturn(List.of());
        LocalDate prior = LocalDate.of(2026, 9, 16);
        DailyCandle valid = candle(prior);

        int saved = service.saveMissing(stock, prior.minusDays(1), prior,
                List.of(valid, valid, candle(prior.plusDays(1))));

        assertThat(saved).isEqualTo(1);
        @SuppressWarnings("unchecked")
        var rows = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(rows.capture());
        StockCandle stored = (StockCandle) rows.getValue().getFirst();
        assertThat(stored.getTimeframe()).isEqualTo("1D");
        assertThat(stored.isFinalCandle()).isTrue();
        assertThat(stored.getStartTime()).isEqualTo(prior.atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant());
        assertThat(stored.getSource().name()).isEqualTo("BACKFILL");
    }

    private DailyCandle candle(LocalDate date) {
        return new DailyCandle(date, BigDecimal.valueOf(100), BigDecimal.valueOf(105),
                BigDecimal.valueOf(99), BigDecimal.valueOf(102), 10, BigDecimal.valueOf(1000));
    }
}
