package com.sunmo.stockplatform.closing;

import com.sunmo.stockplatform.closing.application.*;
import com.sunmo.stockplatform.closing.config.*;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import com.sunmo.stockplatform.closing.domain.OvernightPerformanceStatus;
import com.sunmo.stockplatform.closing.infrastructure.ClosingCandidateObservationRepository;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRepository;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRunRepository;
import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
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
    private final ClosingCandidateObservationService observations = mock(ClosingCandidateObservationService.class);
    private final ClosingCandidateObservationRepository observationRows = mock(ClosingCandidateObservationRepository.class);
    private final ClosingRecommendationRepository repository = mock(ClosingRecommendationRepository.class);
    private final ClosingRecommendationRunRepository runs = mock(ClosingRecommendationRunRepository.class);
    private final RealtimeSubscriptionRegistry subscriptions = mock(RealtimeSubscriptionRegistry.class);
    private final ClosingTradingCalendar calendar = mock(ClosingTradingCalendar.class);

    @Test
    void officialRunTracksPreviousSessionAndProtectsBothDates() {
        ClosingRecommendationScheduler scheduler = scheduler(at(TUESDAY, 15, 15), true);
        when(calendar.previousTradingDay(TUESDAY)).thenReturn(MONDAY);
        ClosingRecommendationRun previousRun = run(1L);
        ClosingRecommendationRun currentRun = run(2L);
        when(runs.findByRequestKey(key(MONDAY))).thenReturn(Optional.of(previousRun));
        when(runs.findByRequestKey(key(TUESDAY)))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(currentRun));
        when(repository.findStockCodesByRunId(1L)).thenReturn(List.of("005930"));
        when(repository.findStockCodesByRunId(2L)).thenReturn(List.of("000660"));

        scheduler.poll();
        scheduler.poll();

        verify(recommendations).generate(TUESDAY, 10, bd("35"), bd("65"),
                "official-2026-09-22-closing-recommend-v9-1515");
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
        when(repository.findStockCodesByRunId(1L)).thenReturn(List.of("005930"));

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
        when(repository.findStockCodesByRunId(1L)).thenReturn(List.of("005930"));

        scheduler.poll();

        verifyNoInteractions(recommendations, performances);
        verify(subscriptions).add("005930", RealtimeSubscriptionRegistry.Source.OVERNIGHT);
    }

    private ClosingRecommendationScheduler scheduler(Instant now, boolean tradingDay) {
        when(calendar.now()).thenReturn(now);
        LocalDate date = now.atZone(ClosingTradingCalendar.ZONE).toLocalDate();
        when(calendar.isTradingDay(date)).thenReturn(tradingDay);
        doAnswer(invocation -> invocation.<LocalDate>getArgument(0)
                .atTime(15, 30).atZone(ClosingTradingCalendar.ZONE).toInstant()).when(calendar).close(any());
        return new ClosingRecommendationScheduler(recommendations, performances, observations, observationRows,
                repository, runs, subscriptions, calendar,
                new ClosingRecommendationProperties(20, 4, bd("55"), LocalTime.of(14, 30),
                        LocalTime.of(15, 15), LocalTime.of(15, 20), Duration.ZERO),
                new ClosingAutomationProperties(true, 10, bd("35"), bd("65"), bd("3"), bd("-2")));
    }

    @Test void noAutomaticRunBefore1515OrAt1520AndRestartDoesNotDuplicate() {
        var scheduler = scheduler(at(TUESDAY, 15, 14), true);
        when(calendar.previousTradingDay(TUESDAY)).thenReturn(MONDAY);
        scheduler.poll();
        when(calendar.now()).thenReturn(at(TUESDAY, 15, 20));
        scheduler.poll();
        verifyNoInteractions(recommendations);
        var existing = run(2L);
        when(runs.findByRequestKey(key(TUESDAY))).thenReturn(Optional.of(existing));
        scheduler = scheduler(at(TUESDAY, 15, 16), true);
        scheduler.poll();
        verifyNoInteractions(recommendations);
    }

    @Test
    void candidateObservationsWaitForTheCloseAndThenRunOncePerDay() {
        var scheduler = scheduler(at(TUESDAY, 15, 39), true);
        when(calendar.previousTradingDay(TUESDAY)).thenReturn(MONDAY);
        ClosingRecommendationRun previousRun = run(1L);
        when(runs.findByRequestKey(key(MONDAY))).thenReturn(Optional.of(previousRun));
        ClosingRecommendationRun currentRun = run(2L);
        when(runs.findByRequestKey(key(TUESDAY))).thenReturn(Optional.of(currentRun));
        when(observationRows.findRunIdsByStatusInAndExpectedSessionDateOnOrBefore(anyCollection(), eq(TUESDAY)))
                .thenReturn(List.of(7L, 1L));

        scheduler.poll();
        verifyNoInteractions(observations);

        when(calendar.now()).thenReturn(at(TUESDAY, 15, 40));
        scheduler.poll();
        when(calendar.now()).thenReturn(at(TUESDAY, 16, 10));
        scheduler.poll();

        verify(observationRows).findRunIdsByStatusInAndExpectedSessionDateOnOrBefore(
                List.of(OvernightPerformanceStatus.PENDING, OvernightPerformanceStatus.IN_PROGRESS), TUESDAY);
        verify(observations).track(1L, bd("3"), bd("-2"));
        verify(observations).track(7L, bd("3"), bd("-2"));
        verifyNoMoreInteractions(observations);
    }

    @Test
    void oneFailedObservationRunDoesNotBlockTheOthers() {
        var scheduler = scheduler(at(TUESDAY, 16, 0), true);
        when(calendar.previousTradingDay(TUESDAY)).thenReturn(MONDAY);
        ClosingRecommendationRun currentRun = run(2L);
        when(runs.findByRequestKey(key(TUESDAY))).thenReturn(Optional.of(currentRun));
        when(observationRows.findRunIdsByStatusInAndExpectedSessionDateOnOrBefore(anyCollection(), eq(TUESDAY)))
                .thenReturn(List.of(3L, 4L));
        when(observations.track(eq(3L), any(), any())).thenThrow(new IllegalStateException("Only completed forward runs"));

        scheduler.poll();

        verify(observations).track(4L, bd("3"), bd("-2"));
    }

    @Test
    void nonTradingDayDoesNotTrackCandidateObservations() {
        LocalDate saturday = LocalDate.of(2026, 9, 26);
        var scheduler = scheduler(at(saturday, 18, 0), false);
        when(calendar.previousTradingDay(saturday)).thenReturn(LocalDate.of(2026, 9, 25));

        scheduler.poll();

        verifyNoInteractions(observations, observationRows);
    }

    private ClosingRecommendationRun run(Long id) {
        ClosingRecommendationRun run = mock(ClosingRecommendationRun.class);
        when(run.getId()).thenReturn(id);
        return run;
    }

    private String key(LocalDate date) {
        return "official-" + date + "-closing-recommend-v9-1515";
    }

    private Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(ClosingTradingCalendar.ZONE).toInstant();
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
