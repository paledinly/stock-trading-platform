package com.sunmo.stockplatform.closing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.application.*;
import com.sunmo.stockplatform.closing.domain.*;
import com.sunmo.stockplatform.closing.infrastructure.*;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OvernightPerformanceServiceTest {
    private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 11);
    private static final LocalDate MONDAY = FRIDAY.plusDays(3);
    private final ClosingRecommendationRepository recommendations = mock(ClosingRecommendationRepository.class);
    private final OvernightPerformanceRepository performances = mock(OvernightPerformanceRepository.class);
    private final StockCandleRepository candles = mock(StockCandleRepository.class);
    private final ClosingRecommendation recommendation = mock(ClosingRecommendation.class);

    private OvernightPerformanceService service(LocalDate session, LocalTime now, List<LocalDate> holidays) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 0, 0, false,
                null, null, null, null, null, holidays), Clock.fixed(at(session, now), ZoneOffset.UTC));
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(1L);
        when(recommendation.getStock()).thenReturn(stock);
        when(recommendation.getId()).thenReturn(10L);
        when(recommendation.getRecommendationDate()).thenReturn(FRIDAY);
        when(recommendation.getBuyReferencePrice()).thenReturn(new BigDecimal("100"));
        when(recommendations.findByRecommendationDateOrderByRankAsc(FRIDAY)).thenReturn(List.of(recommendation));
        when(recommendations.findLockedById(10L)).thenReturn(Optional.of(recommendation));
        when(performances.save(any())).thenAnswer(i -> i.getArgument(0));
        return new OvernightPerformanceService(recommendations, performances, candles, calendar, new ObjectMapper());
    }

    @Test
    void morningObservationNeverBecomesFinalCloseAndFutureFinalFlagsAreIgnored() {
        var service = service(MONDAY, LocalTime.of(9, 13), List.of());
        stubCandles(bars(MONDAY, 3));
        var row = service.track(FRIDAY, null, null, null).performances().getFirst();
        assertThat(row.status()).isEqualTo("IN_PROGRESS");
        assertThat(row.closePrice()).isNull();
        assertThat(row.closeReturnRate()).isNull();
        assertThat(row.latestReturnRate()).isNotNull();
        assertThat(row.observedThrough()).isEqualTo(at(MONDAY, LocalTime.of(9, 10)));
        assertThat(row.expectedSessionDate()).isEqualTo(MONDAY);
    }

    @Test
    void holidayMovesExpectedSessionButMissingMondayDoesNotMoveItToTuesday() {
        var service = service(MONDAY.plusDays(1), LocalTime.of(16, 0), List.of());
        stubCandles(bars(MONDAY.plusDays(1), 79));
        var row = service.track(FRIDAY, null, null, null).performances().getFirst();
        assertThat(row.expectedSessionDate()).isEqualTo(MONDAY);
        assertThat(row.status()).isEqualTo("DATA_INCOMPLETE");
        assertThat(row.latestPrice()).isNull();
        service = service(MONDAY.plusDays(1), LocalTime.of(16, 0), List.of(MONDAY));
        row = service.track(FRIDAY, null, null, null).performances().getFirst();
        assertThat(row.expectedSessionDate()).isEqualTo(MONDAY.plusDays(1));
        assertThat(row.status()).isEqualTo("COMPLETED");
    }

    @Test
    void missingClosingPrintOrInteriorCandlePreventsCompletion() {
        var service = service(MONDAY, LocalTime.of(16, 0), List.of());
        stubCandles(bars(MONDAY, 78));
        var row = service.track(FRIDAY, null, null, null).performances().getFirst();
        assertThat(row.status()).isEqualTo("DATA_INCOMPLETE");
        assertThat(row.missingIntervals()).contains("06:30:00Z");
        assertThat(row.closePrice()).isNull();
        var missing = new ArrayList<>(bars(MONDAY, 79));
        missing.remove(2);
        stubCandles(missing);
        assertThat(service.track(FRIDAY, null, null, null).performances().getFirst().status()).isEqualTo("DATA_INCOMPLETE");
    }

    @Test
    void completedObservationAndLegacyRecordAreNeverRecalculated() {
        var service = service(MONDAY, LocalTime.of(16, 0), List.of());
        stubCandles(bars(MONDAY, 79));
        service.track(FRIDAY, null, null, null);
        var capture = org.mockito.ArgumentCaptor.forClass(OvernightPerformance.class);
        verify(performances).save(capture.capture());
        when(performances.findByRecommendationId(10L)).thenReturn(Optional.of(capture.getValue()));
        clearInvocations(candles, performances);
        var row = service.track(FRIDAY, null, null, null).performances().getFirst();
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.targetHit()).isTrue();
        assertThat(row.stopHit()).isTrue();
        verifyNoInteractions(candles);
        verify(performances, never()).save(any());
        when(performances.findByRecommendationId(10L)).thenReturn(Optional.of(new OvernightPerformance(recommendation)));
        assertThat(service.track(FRIDAY, null, null, null).performances().getFirst().calculationVersion())
                .isEqualTo(OvernightPerformance.VERSION);
        verifyNoInteractions(candles);
    }

    @Test
    void beforeNextSessionIsPendingAndThresholdsCannotBeChanged() {
        var service = service(FRIDAY.plusDays(1), LocalTime.NOON, List.of());
        stubCandles(List.of());
        var row = service.track(FRIDAY, null, null, null).performances().getFirst();
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.expectedSessionDate()).isEqualTo(MONDAY);
        var capture = org.mockito.ArgumentCaptor.forClass(OvernightPerformance.class);
        verify(performances).save(capture.capture());
        when(performances.findByRecommendationId(10L)).thenReturn(Optional.of(capture.getValue()));
        assertThatThrownBy(() -> service.track(FRIDAY, BigDecimal.ONE, null, null))
                .isInstanceOf(com.sunmo.stockplatform.common.error.ApplicationException.class);
    }

    private void stubCandles(List<StockCandle> rows) {
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                anyLong(), anyString(), any(), any())).thenReturn(rows);
    }

    private List<StockCandle> bars(LocalDate date, int count) {
        return IntStream.range(0, count).mapToObj(index -> {
            StockCandle candle = mock(StockCandle.class);
            when(candle.getStartTime()).thenReturn(at(date, LocalTime.of(9, 0)).plusSeconds(index * 300L));
            when(candle.isFinalCandle()).thenReturn(true);
            when(candle.getOpen()).thenReturn(new BigDecimal("100"));
            when(candle.getClose()).thenReturn(new BigDecimal("101"));
            when(candle.getHigh()).thenReturn(new BigDecimal("104"));
            when(candle.getLow()).thenReturn(new BigDecimal("97"));
            return candle;
        }).toList();
    }

    private Instant at(LocalDate date, LocalTime time) { return date.atTime(time).atZone(ClosingTradingCalendar.ZONE).toInstant(); }
}
