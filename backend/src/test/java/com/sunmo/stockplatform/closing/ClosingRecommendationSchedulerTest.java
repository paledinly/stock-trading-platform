package com.sunmo.stockplatform.closing;

import com.sunmo.stockplatform.closing.application.*;
import com.sunmo.stockplatform.closing.config.*;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRepository;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRunRepository;
import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClosingRecommendationSchedulerTest {
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 21);
    private static final LocalDate TUESDAY = MONDAY.plusDays(1);

    private final ClosingRecommendationService recommendations = mock(ClosingRecommendationService.class);
    private final OvernightPerformanceService performances = mock(OvernightPerformanceService.class);
    private final ClosingRecommendationRepository repository = mock(ClosingRecommendationRepository.class);
    private final ClosingRecommendationRunRepository runs = mock(ClosingRecommendationRunRepository.class);
    private final RealtimeSubscriptionRegistry subscriptions = mock(RealtimeSubscriptionRegistry.class);
    private final ClosingTradingCalendar calendar = mock(ClosingTradingCalendar.class);

    @Test
    void officialRunTracksPreviousSessionAndProtectsBothDates() {
        ClosingRecommendationScheduler scheduler = scheduler(at(TUESDAY, 15, 5), true);
        when(calendar.previousTradingDay(TUESDAY)).thenReturn(MONDAY);
        ClosingRecommendationRun previousRun = run(1L);
        ClosingRecommendationRun currentRun = run(2L);
        when(runs.findByRequestKey(key(MONDAY))).thenReturn(Optional.of(previousRun));
        when(runs.findByRequestKey(key(TUESDAY)))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(currentRun));
        ClosingRecommendation previous = row("005930");
        ClosingRecommendation current = row("000660");
        when(repository.findByRunIdOrderByRankAsc(1L)).thenReturn(List.of(previous));
        when(repository.findByRunIdOrderByRankAsc(2L)).thenReturn(List.of(current));

        scheduler.poll();
        scheduler.poll();

        verify(recommendations).generate(TUESDAY, 10, bd("35"), bd("65"),
                "official-2026-09-22-closing-recommend-v8-1500");
        verify(performances).track(MONDAY, bd("3"), bd("-2"), 1L);
        verify(subscriptions, atLeastOnce()).add("005930", RealtimeSubscriptionRegistry.Source.OVERNIGHT);
        verify(subscriptions, atLeastOnce()).add("000660", RealtimeSubscriptionRegistry.Source.OVERNIGHT);
    }

    @Test
    void beforeDecisionTimeOnlyPreviousRecommendationIsProtected() {
        ClosingRecommendationScheduler scheduler = scheduler(at(TUESDAY, 14, 59), true);
        when(calendar.previousTradingDay(TUESDAY)).thenReturn(MONDAY);
        ClosingRecommendationRun previousRun = run(1L);
        when(runs.findByRequestKey(key(MONDAY))).thenReturn(Optional.of(previousRun));
        ClosingRecommendation previous = row("005930");
        when(repository.findByRunIdOrderByRankAsc(1L)).thenReturn(List.of(previous));

        scheduler.poll();

        verifyNoInteractions(recommendations);
        verify(subscriptions).add("005930", RealtimeSubscriptionRegistry.Source.OVERNIGHT);
    }

    @Test
    void nonTradingDayDoesNotGenerateOrTrackButKeepsLastRecommendation() {
        LocalDate saturday = LocalDate.of(2026, 9, 26);
        ClosingRecommendationScheduler scheduler = scheduler(at(saturday, 10, 0), false);
        when(calendar.previousTradingDay(saturday)).thenReturn(LocalDate.of(2026, 9, 25));
        ClosingRecommendationRun previousRun = run(1L);
        when(runs.findByRequestKey(key(LocalDate.of(2026, 9, 25)))).thenReturn(Optional.of(previousRun));
        ClosingRecommendation previous = row("005930");
        when(repository.findByRunIdOrderByRankAsc(1L)).thenReturn(List.of(previous));

        scheduler.poll();

        verifyNoInteractions(recommendations, performances);
        verify(subscriptions).add("005930", RealtimeSubscriptionRegistry.Source.OVERNIGHT);
    }

    private ClosingRecommendationScheduler scheduler(Instant now, boolean tradingDay) {
        when(calendar.now()).thenReturn(now);
        LocalDate date = now.atZone(ClosingTradingCalendar.ZONE).toLocalDate();
        when(calendar.isTradingDay(date)).thenReturn(tradingDay);
        return new ClosingRecommendationScheduler(recommendations, performances, repository, runs, subscriptions, calendar,
                new ClosingRecommendationProperties(20, 4, bd("55"), LocalTime.of(14, 30),
                        LocalTime.of(15, 0), LocalTime.of(15, 20), Duration.ofSeconds(10)),
                new ClosingAutomationProperties(true, 10, bd("35"), bd("65"), bd("3"), bd("-2")));
    }

    private ClosingRecommendation row(String code) {
        ClosingRecommendation recommendation = mock(ClosingRecommendation.class);
        Stock stock = mock(Stock.class);
        when(stock.getStockCode()).thenReturn(code);
        when(recommendation.getStock()).thenReturn(stock);
        return recommendation;
    }

    private ClosingRecommendationRun run(Long id) {
        ClosingRecommendationRun run = mock(ClosingRecommendationRun.class);
        when(run.getId()).thenReturn(id);
        return run;
    }

    private String key(LocalDate date) {
        return "official-" + date + "-closing-recommend-v8-1500";
    }

    private Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(ClosingTradingCalendar.ZONE).toInstant();
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
