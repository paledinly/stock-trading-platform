/*
 * ClosingRecommendationScheduler(마감 추천 자동 실행기)가 언제 공식 추천을 만들고, 전일 추천 성과와 후보 관측을 언제 추적하는지 확인하는 테스트예요.
 * 15:15 평가 창을 벗어나 실행되거나 재시작 때 중복 실행되면 공식 Forward 표본이 오염되고,
 * 다음 거래일 종가 체결 전에 실시간 구독을 끊으면 성과를 계산할 봉이 빠지기 때문에 중요해요.
 */
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

/**
 * 알람 시계처럼 정해진 시각에만 추천·성과 추적·관측을 깨우고, 필요한 종목의 실시간 구독을 유지하는지 확인해요.
 *
 * <p>모든 의존 객체는 목(mock, 가짜 객체)이고, 현재 시각은 거래일 달력 목의 now()로 고정해요.
 * MONDAY(2026-09-21)가 전 거래일, TUESDAY(2026-09-22)가 오늘인 상황을 주로 써요.
 */
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

    /**
     * 15:15 공식 실행이 오늘 추천을 한 번 만들고, 전 거래일 추천 성과를 추적하며, 두 날짜의 종목을 모두 구독으로 보호하는지 확인해요.
     *
     * <p>입력: 화요일 15:15, 월요일 run(종목 005930)이 있고 화요일 run은 두 번 조회될 때까지 없다가 세 번째에 생겨요.
     * <p>기대: 화요일 추천 생성이 공식 요청 키로 1회 호출되고, 월요일 성과 추적이 호출되며, 005930과 000660이 OVERNIGHT 구독에 추가돼요.
     */
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

        // 실행: 두 번 폴링해도 생성은 한 번만 일어나야 해요(verify 기본값은 정확히 1회).
        scheduler.poll();
        scheduler.poll();

        verify(recommendations).generate(TUESDAY, 10, bd("35"), bd("65"),
                "official-2026-09-22-closing-recommend-v9_1-1515");
        verify(performances).track(MONDAY, bd("3"), bd("-2"), 1L);
        verify(subscriptions, atLeastOnce()).add("005930", RealtimeSubscriptionRegistry.Source.OVERNIGHT);
        verify(subscriptions, atLeastOnce()).add("000660", RealtimeSubscriptionRegistry.Source.OVERNIGHT);
    }

    /**
     * 평가 시각(15:15) 전에는 오늘 추천을 만들지 않고 전일 추천 종목만 구독으로 보호하는지 확인해요.
     *
     * <p>입력: 화요일 14:59, 월요일 run(종목 005930)이 있어요.
     * <p>기대: 추천 서비스 호출이 전혀 없고, 005930만 OVERNIGHT 구독에 추가돼요.
     */
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

    /**
     * 휴장일에는 추천 생성과 성과 추적을 하지 않지만, 마지막 거래일 추천 종목의 구독은 유지하는지 확인해요.
     *
     * <p>입력: 토요일 10:00, 직전 거래일(금요일) run(종목 005930)이 있어요.
     * <p>기대: 추천·성과 서비스 호출이 없고, 005930은 OVERNIGHT 구독에 추가돼요.
     */
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

    /** 관측 후보 구독 상한 2로 스케줄러를 만들어요. */
    private ClosingRecommendationScheduler scheduler(Instant now, boolean tradingDay) {
        return scheduler(now, tradingDay, 2);
    }

    /**
     * 지정한 현재 시각과 거래일 여부로 달력 목을 설정하고, 15:15 입력 마감·유예 0초 설정의 스케줄러를 만들어요.
     * 장 마감(close)은 15:30 KST로 응답하도록 해요.
     */
    private ClosingRecommendationScheduler scheduler(Instant now, boolean tradingDay, int observationLimit) {
        when(calendar.now()).thenReturn(now);
        LocalDate date = now.atZone(ClosingTradingCalendar.ZONE).toLocalDate();
        when(calendar.isTradingDay(date)).thenReturn(tradingDay);
        doAnswer(invocation -> invocation.<LocalDate>getArgument(0)
                .atTime(15, 30).atZone(ClosingTradingCalendar.ZONE).toInstant()).when(calendar).close(any());
        return new ClosingRecommendationScheduler(recommendations, performances, observations, observationRows,
                repository, runs, subscriptions, calendar,
                new ClosingRecommendationProperties(20, 4, bd("55"), LocalTime.of(14, 30),
                        LocalTime.of(15, 15), LocalTime.of(15, 20), Duration.ZERO),
                new ClosingAutomationProperties(true, 10, bd("35"), bd("65"), bd("3"), bd("-2"), observationLimit));
    }

    /**
     * 15:15 전이나 15:20 이후에는 자동 실행하지 않고, 재시작해도 이미 있는 오늘 run을 다시 만들지 않는지 확인해요.
     *
     * <p>입력: 15:14와 15:20에 폴링, 이후 오늘 run이 이미 있는 상태에서 새 스케줄러로 15:16에 폴링해요.
     * <p>기대: 추천 서비스 호출이 한 번도 없어요.
     */
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

    /**
     * 후보 관측 추적이 장 마감 + 10분(15:40)까지 기다렸다가 하루 한 번만 실행되는지 확인해요.
     *
     * <p>입력: 관측 대상 run ID 7, 1이 있고 15:39, 15:40, 16:10에 차례로 폴링해요.
     * <p>기대: 15:39에는 추적하지 않고, 이후 PENDING/IN_PROGRESS 조건으로 조회해 run 1과 7을 각각 정확히 한 번씩만 추적해요.
     */
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

        // 15:39: 아직 마감 + 10분 전이라 관측 추적을 하지 않아요.
        scheduler.poll();
        verify(observations, never()).track(anyLong(), any(), any());

        // 15:40에 한 번 실행되고, 16:10 폴링에서는 같은 날 다시 실행되지 않아야 해요.
        when(calendar.now()).thenReturn(at(TUESDAY, 15, 40));
        scheduler.poll();
        when(calendar.now()).thenReturn(at(TUESDAY, 16, 10));
        scheduler.poll();

        verify(observationRows).findRunIdsByStatusInAndExpectedSessionDateOnOrBefore(
                List.of(OvernightPerformanceStatus.PENDING, OvernightPerformanceStatus.IN_PROGRESS), TUESDAY);
        verify(observations).track(1L, bd("3"), bd("-2"));
        verify(observations).track(7L, bd("3"), bd("-2"));
        verify(observations, times(2)).track(anyLong(), any(), any());
    }

    /**
     * 관측 run 하나가 예외로 실패해도 나머지 run의 관측 추적은 계속되는지 확인해요.
     *
     * <p>입력: 16:00, 관측 대상 run 3과 4가 있고 run 3 추적은 IllegalStateException을 던져요.
     * <p>기대: run 4 추적이 호출돼요.
     */
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

    /**
     * 휴장일에는 후보 관측을 추적하지 않는지 확인해요.
     *
     * <p>입력: 토요일 18:00이에요.
     * <p>기대: 관측 서비스와 관측 저장소에 아무 호출도 없어요.
     */
    @Test
    void nonTradingDayDoesNotTrackCandidateObservations() {
        LocalDate saturday = LocalDate.of(2026, 9, 26);
        var scheduler = scheduler(at(saturday, 18, 0), false);
        when(calendar.previousTradingDay(saturday)).thenReturn(LocalDate.of(2026, 9, 25));

        scheduler.poll();

        verifyNoInteractions(observations, observationRows);
    }

    /**
     * 관측 후보 구독은 공식 추천 종목을 먼저 넣은 뒤 남은 구독 슬롯만큼만 채우는지 확인해요.
     *
     * <p>입력: 공식 종목 005930, 관측 후보 005930·000660·035420, 남은 슬롯은 1개 다음 0개로 응답해요.
     * <p>기대: 005930, 000660 순서로 추가되고 035420은 추가되지 않아요.
     */
    @Test
    void observationCandidatesFillOnlyFreeSlotsAfterOfficialCodes() {
        var scheduler = scheduler(at(TUESDAY, 15, 16), true);
        when(calendar.previousTradingDay(TUESDAY)).thenReturn(MONDAY);
        ClosingRecommendationRun currentRun = run(2L);
        when(runs.findByRequestKey(key(TUESDAY))).thenReturn(Optional.of(currentRun));
        when(repository.findStockCodesByRunId(2L)).thenReturn(List.of("005930"));
        when(observations.subscriptionCandidates(currentRun, 2)).thenReturn(List.of("005930", "000660", "035420"));
        when(subscriptions.remaining()).thenReturn(1, 0);

        scheduler.poll();

        // inOrder(호출 순서 검증): 공식 종목이 관측 후보보다 먼저 구독돼야 해요.
        var order = inOrder(subscriptions);
        order.verify(subscriptions).add("005930", RealtimeSubscriptionRegistry.Source.OVERNIGHT);
        order.verify(subscriptions).add("000660", RealtimeSubscriptionRegistry.Source.OVERNIGHT);
        verify(subscriptions, never()).add(eq("035420"), any());
    }

    /**
     * 관측 후보 상한이 0이면 관측 후보를 조회하지 않고 공식 추천 종목만 구독하는지 확인해요.
     *
     * <p>입력: observationLimit 0, 공식 종목 005930이에요.
     * <p>기대: subscriptionCandidates 호출이 없고 005930만 구독에 추가돼요.
     */
    @Test
    void zeroObservationLimitKeepsOnlyOfficialCodes() {
        var scheduler = scheduler(at(TUESDAY, 15, 16), true, 0);
        when(calendar.previousTradingDay(TUESDAY)).thenReturn(MONDAY);
        ClosingRecommendationRun currentRun = run(2L);
        when(runs.findByRequestKey(key(TUESDAY))).thenReturn(Optional.of(currentRun));
        when(repository.findStockCodesByRunId(2L)).thenReturn(List.of("005930"));

        scheduler.poll();

        verify(observations, never()).subscriptionCandidates(any(), anyInt());
        verify(subscriptions).add("005930", RealtimeSubscriptionRegistry.Source.OVERNIGHT);
    }

    /**
     * 전일 추천 종목 구독이 종가 단일가의 랜덤 종료(15:30:00~15:30:30) 체결이 들어올 때까지 유지되는지 확인해요.
     *
     * <p>입력: 15:25, 15:30:20, 15:35에 차례로 폴링해요.
     * <p>기대: 15:30:20까지는 005930 구독을 제거하지 않고, 15:35에는 제거해요.
     */
    @Test
    void previousRunStaysSubscribedThroughTheRandomEndClosingPrint() {
        var scheduler = scheduler(at(TUESDAY, 15, 25), true);
        when(calendar.previousTradingDay(TUESDAY)).thenReturn(MONDAY);
        ClosingRecommendationRun previousRun = run(1L);
        when(runs.findByRequestKey(key(MONDAY))).thenReturn(Optional.of(previousRun));
        when(repository.findStockCodesByRunId(1L)).thenReturn(List.of("005930"));
        scheduler.poll();

        // First poll of the 15:30 bucket: the closing print (15:30:00..15:30:30) has not necessarily arrived.
        when(calendar.now()).thenReturn(at(TUESDAY, 15, 30).plusSeconds(20));
        scheduler.poll();
        verify(subscriptions, never()).remove("005930", RealtimeSubscriptionRegistry.Source.OVERNIGHT);

        when(calendar.now()).thenReturn(at(TUESDAY, 15, 35));
        scheduler.poll();
        verify(subscriptions).remove("005930", RealtimeSubscriptionRegistry.Source.OVERNIGHT);
    }

    /** 지정한 ID를 돌려주는 추천 run 목을 만들어요. */
    private ClosingRecommendationRun run(Long id) {
        ClosingRecommendationRun run = mock(ClosingRecommendationRun.class);
        when(run.getId()).thenReturn(id);
        return run;
    }

    /** 날짜별 공식 실행 요청 키(official-날짜-전략버전)를 만들어요. */
    private String key(LocalDate date) {
        return "official-" + date + "-closing-recommend-v9_1-1515";
    }

    /** 한국 시간(ClosingTradingCalendar.ZONE) 기준 날짜·시·분을 Instant로 바꿔요. */
    private Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(ClosingTradingCalendar.ZONE).toInstant();
    }

    /** 문자열로 BigDecimal을 만드는 짧은 도우미예요. */
    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
