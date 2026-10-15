/*
 * 장 마감 10분 뒤 후보 관측 순서 테스트예요. 분봉 보완은 관측 행에서 정밀 후보를 찾기 때문에, 전 거래일 run을 먼저 한 번 평가해
 * 행을 만든 뒤 보완하고, 그다음 다시 평가해야 해요. 2026-10-15 15:40 첫 실행은 이 순서가 아니어서 보완 대상이 0개였어요.
 */
package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.closing.config.ClosingAutomationProperties;
import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import com.sunmo.stockplatform.closing.infrastructure.ClosingCandidateObservationRepository;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRepository;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRunRepository;
import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ClosingObservationOrderTest {
    @Test
    @SuppressWarnings("unchecked")
    void tracksThePreviousRunBeforeBackfillSoTheFirstBackfillHasTargets() {
        // 2026-10-02(금) 16:00 KST, 전 거래일 10-01의 공식 run은 64번이에요.
        Instant now = Instant.parse("2026-10-02T07:00:00Z");
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(now, ZoneOffset.UTC));
        var observations = mock(ClosingCandidateObservationService.class);
        var observationRows = mock(ClosingCandidateObservationRepository.class);
        when(observationRows.findRunIdsByStatusInAndExpectedSessionDateOnOrBefore(any(), any())).thenReturn(List.of());
        var runs = mock(ClosingRecommendationRunRepository.class);
        var run = mock(ClosingRecommendationRun.class);
        when(run.getId()).thenReturn(64L);
        when(runs.findByRequestKey(anyString())).thenReturn(Optional.of(run));
        var scheduler = new ClosingRecommendationScheduler(mock(ClosingRecommendationService.class),
                mock(OvernightPerformanceService.class), observations, observationRows,
                mock(ClosingRecommendationRepository.class), runs, mock(RealtimeSubscriptionRegistry.class), calendar,
                new ClosingRecommendationProperties(20, 4, null, null, null, null, null),
                new ClosingAutomationProperties(true, 10, null, null, new BigDecimal("3"), new BigDecimal("-2"), null));
        var backfill = mock(ObservationBackfillService.class);
        ObjectProvider<ObservationBackfillService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(backfill);
        ReflectionTestUtils.invokeMethod(scheduler, "setObservationBackfill", provider);

        ReflectionTestUtils.invokeMethod(scheduler, "trackCandidateObservations", LocalDate.of(2026, 10, 2), now);

        InOrder order = inOrder(observations, backfill);
        order.verify(observations).track(eq(64L), any(), any());
        order.verify(backfill).fillTodayLogged();
        order.verify(observations).track(eq(64L), any(), any());
        verifyNoMoreInteractions(backfill);
    }

    private static long eq(long value) { return org.mockito.ArgumentMatchers.eq(value); }
}
