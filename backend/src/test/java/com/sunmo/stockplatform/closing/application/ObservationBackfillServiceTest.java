package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.candle.application.CandleBackfillService;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRunRepository;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ObservationBackfillServiceTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 2);
    static final LocalDate PREVIOUS = LocalDate.of(2026, 10, 1);
    final ClosingRecommendationRunRepository runs = mock(ClosingRecommendationRunRepository.class);
    final StockRepository stocks = mock(StockRepository.class);
    final StockCandleRepository candles = mock(StockCandleRepository.class);
    final CandleBackfillService backfill = mock(CandleBackfillService.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    ObservationBackfillService service(String utc) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse(utc), ZoneOffset.UTC));
        return new ObservationBackfillService(calendar, runs, stocks, candles, backfill, jdbc);
    }

    @Test
    void fillsMissingBarsOfPreviousRunPrecisionCandidatesExceptTheClosingCall() {
        ClosingRecommendationRun run = mock(ClosingRecommendationRun.class);
        when(run.getId()).thenReturn(63L);
        when(runs.findFirstByRecommendationDateAndExecutionModeOrderByIdDesc(PREVIOUS, "FORWARD"))
                .thenReturn(Optional.of(run));
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(63L), eq(63L))).thenReturn(List.of(7L));
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(7L);
        when(stocks.findById(7L)).thenReturn(Optional.of(stock));
        // Realtime bars 09:00..15:10 exist; 15:15 is missing; 15:25 is a flat zero-volume placeholder.
        List<StockCandle> stored = new ArrayList<>();
        Instant open = Instant.parse("2026-10-02T00:00:00Z");
        for (Instant t = open; t.isBefore(Instant.parse("2026-10-02T06:15:00Z")); t = t.plusSeconds(300))
            stored.add(bar(t, 10));
        stored.add(bar(Instant.parse("2026-10-02T06:25:00Z"), 0));
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(7L), eq("5M"), any(), any())).thenReturn(stored);

        var result = service("2026-10-02T06:33:00Z").fillToday();

        @SuppressWarnings("unchecked") ArgumentCaptor<List<Instant>> gaps = ArgumentCaptor.forClass(List.class);
        verify(backfill).backfillToday(eq(stock), gaps.capture(), any());
        assertThat(gaps.getValue()).containsExactly(Instant.parse("2026-10-02T06:15:00Z"),
                Instant.parse("2026-10-02T06:25:00Z"));
        assertThat(result.runId()).isEqualTo(63L);
        assertThat(result.stillMissingStocks()).isEqualTo(1);
    }

    @Test
    void doesNothingBeforeTheClosingPrintIsFinal() {
        assertThat(service("2026-10-02T06:31:00Z").fillToday()).isNull();
        verifyNoInteractions(backfill);
    }

    private static StockCandle bar(Instant start, long volume) {
        StockCandle candle = mock(StockCandle.class);
        when(candle.getStartTime()).thenReturn(start);
        when(candle.isFinalCandle()).thenReturn(true);
        when(candle.getVolume()).thenReturn(volume);
        return candle;
    }
}
