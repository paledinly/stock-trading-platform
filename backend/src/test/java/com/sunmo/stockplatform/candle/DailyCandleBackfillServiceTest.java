package com.sunmo.stockplatform.candle;

import com.sunmo.stockplatform.candle.application.DailyCandleBackfillService;
import com.sunmo.stockplatform.candle.application.DailyCandlePersistence;
import com.sunmo.stockplatform.candle.config.DailyCandleBackfillProperties;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.kis.candle.DailyCandle;
import com.sunmo.stockplatform.kis.candle.KisDailyCandleClient;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import com.sunmo.stockplatform.scanner.infrastructure.ScannerDetectionRepository;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import com.sunmo.stockplatform.watchlist.infrastructure.WatchlistItemRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DailyCandleBackfillServiceTest {
    @Test
    void afterClosePreparesNextTradingDayWithoutRequestingItsFutureCandle() {
        var settings = new DailyCandleBackfillProperties(true, 40, 120, Duration.ofHours(1));
        var kis = mock(KisProperties.class);
        when(kis.enabled()).thenReturn(true);
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 0, 0, false,
                null, null, null, null, null, List.of()),
                Clock.fixed(Instant.parse("2026-09-17T06:50:00Z"), ZoneOffset.UTC));
        var detections = mock(ScannerDetectionRepository.class);
        when(detections.findRecentStockCodesForSession(eq(LocalDate.of(2026, 9, 17)), any(), any()))
                .thenReturn(List.of("005930", "005930"));
        var watchlist = mock(WatchlistItemRepository.class);
        var stocks = mock(StockRepository.class);
        var client = mock(KisDailyCandleClient.class);
        var persistence = mock(DailyCandlePersistence.class);
        Stock stock = mock(Stock.class);
        when(watchlist.findDistinctStockCodesByOwnerId(1L)).thenReturn(List.of("005930"));
        when(stocks.findByStockCodeAndActiveTrue("005930")).thenReturn(Optional.of(stock));
        when(persistence.ready(stock, LocalDate.of(2026, 9, 17))).thenReturn(false, true);
        when(client.fetch(eq("005930"), any(), eq(LocalDate.of(2026, 9, 17))))
                .thenReturn(List.of(mock(DailyCandle.class)));
        when(persistence.saveMissing(eq(stock), any(), eq(LocalDate.of(2026, 9, 17)), anyList()))
                .thenReturn(61);
        var service = new DailyCandleBackfillService(settings, kis, calendar, detections, watchlist,
                stocks, client, persistence);

        var result = service.prepare();

        assertThat(result.recommendationDate()).isEqualTo(LocalDate.of(2026, 9, 18));
        assertThat(result.latestRequiredDate()).isEqualTo(LocalDate.of(2026, 9, 17));
        assertThat(result.attemptedStocks()).isEqualTo(1);
        assertThat(result.savedCandles()).isEqualTo(61);
        assertThat(result.readyStocks()).isEqualTo(1);
        verify(client).fetch(eq("005930"), any(), eq(LocalDate.of(2026, 9, 17)));
        verify(detections, never()).findByDetectedAtGreaterThanEqualOrderByDetectedAtDesc(any(), any());
    }

    @Test
    void disabledSchedulerMakesNoKisCalls() {
        var kis = mock(KisProperties.class);
        var client = mock(KisDailyCandleClient.class);
        var service = new DailyCandleBackfillService(
                new DailyCandleBackfillProperties(false, 40, 120, Duration.ofHours(1)), kis,
                mock(ClosingTradingCalendar.class), mock(ScannerDetectionRepository.class),
                mock(WatchlistItemRepository.class), mock(StockRepository.class), client,
                mock(DailyCandlePersistence.class));
        service.scheduledPrepare();
        verifyNoInteractions(client);
    }
}
