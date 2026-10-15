/*
 * 마감 추천 자동화를 맡는 스케줄러(정해진 간격으로 자동 실행하는 장치)예요.
 * 거래일 입력 마감(15:15)~진입 마감(15:20) 사이에 공식 FORWARD 평가를 한 번 만들고, 장중에는 전날 추천 성과를,
 * 장 마감 10분 뒤에는 후보 관측을 갱신하며, 추천·관측 종목의 실시간 구독(OVERNIGHT)을 유지해요.
 * closing.automation.enabled=true일 때만 켜져요. ObservationBackfillService는 이 스케줄러가 부르는 순서에 맞춰 동작해요.
 */
package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.candle.application.FiveMinuteCandleAggregator;
import com.sunmo.stockplatform.closing.config.ClosingAutomationProperties;
import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import com.sunmo.stockplatform.closing.domain.OvernightPerformanceStatus;
import com.sunmo.stockplatform.closing.infrastructure.ClosingCandidateObservationRepository;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRunRepository;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRepository;
import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 마감 추천의 하루 일정을 자동으로 돌리는 당번이에요.
 * 30초마다 시계를 보고 "지금 평가할 때인가, 어제 추천 성적을 볼 때인가, 장 끝나고 관측을 정리할 때인가"를 확인해
 * 해당하는 일을 하루(또는 5분)에 한 번씩만 처리하는 알람 시계 같은 역할이에요. 실주문은 하지 않아요.
 */
@Component
@ConditionalOnProperty(prefix = "closing.automation", name = "enabled", havingValue = "true")
public class ClosingRecommendationScheduler {
    private static final Logger log = LoggerFactory.getLogger(ClosingRecommendationScheduler.class);
    /**
     * Lets the last 5-minute bar finalize before unselected candidates are judged.
     * 장 마감(15:30) 뒤 후보 관측까지 기다리는 시간(10분)이에요.
     */
    private static final Duration OBSERVATION_DELAY = Duration.ofMinutes(10);
    // KIS가 일부 종목의 15:30 종가 단일가 분봉 체결량을 장 마감 뒤 30분 넘게 지나서 채워요(2026-10-15 008970: 15:53·16:00엔 0,
    // 16:20엔 19,138). 그래서 이 시각에 분봉 보완과 전일 후보 관측 재평가를 한 번 더 해요(ADR-010 보완 기록).
    static final LocalTime LATE_OBSERVATION_AT = LocalTime.of(18, 0);
    // 다시 평가해야 하는 관측 상태(아직 시작 전, 진행 중)예요.
    private static final List<OvernightPerformanceStatus> AWAITING_EVALUATION =
            List.of(OvernightPerformanceStatus.PENDING, OvernightPerformanceStatus.IN_PROGRESS);

    private final ClosingRecommendationService recommendations;
    private final OvernightPerformanceService performances;
    private final ClosingCandidateObservationService observations;
    private final ClosingCandidateObservationRepository observationRows;
    private final ClosingRecommendationRepository repository;
    private final ClosingRecommendationRunRepository runs;
    private final RealtimeSubscriptionRegistry subscriptions;
    private final ClosingTradingCalendar calendar;
    private final ClosingRecommendationProperties recommendationProperties;
    private final ClosingAutomationProperties automationProperties;
    // 이 스케줄러가 OVERNIGHT로 붙잡아 둔 종목 코드예요. 다음 동기화에서 필요 없어진 것만 해제해요.
    private final Set<String> protectedCodes = new HashSet<>();
    private org.springframework.beans.factory.ObjectProvider<ObservationBackfillService> observationBackfill;

    /**
     * 관측 백필 서비스(있을 때만)를 주입받아요.
     * 받는 것: 백필 서비스 제공자. 돌려주는 것: 없음.
     */
    @org.springframework.beans.factory.annotation.Autowired
    void setObservationBackfill(org.springframework.beans.factory.ObjectProvider<ObservationBackfillService> value) {
        this.observationBackfill = value;
    }
    // 같은 5분 구간에 구독 동기화·성과 추적을 반복하지 않기 위한 마지막 처리 구간과, 관측을 처리한 마지막 날짜예요(메모리에만 있어요).
    private long lastSubscriptionBucket = Long.MIN_VALUE;
    private long lastPerformanceBucket = Long.MIN_VALUE;
    private LocalDate lastObservationDate;
    private LocalDate lastLateObservationDate;

    /**
     * 추천·성과·관측 서비스, 저장소, 실시간 구독 등록부, 거래일 달력, 추천 정책과 자동화 설정을 받아요.
     */
    public ClosingRecommendationScheduler(ClosingRecommendationService recommendations,
            OvernightPerformanceService performances, ClosingCandidateObservationService observations,
            ClosingCandidateObservationRepository observationRows, ClosingRecommendationRepository repository,
            ClosingRecommendationRunRepository runs, RealtimeSubscriptionRegistry subscriptions,
            ClosingTradingCalendar calendar,
            ClosingRecommendationProperties recommendationProperties,
            ClosingAutomationProperties automationProperties) {
        this.recommendations = recommendations;
        this.performances = performances;
        this.observations = observations;
        this.observationRows = observationRows;
        this.repository = repository;
        this.runs = runs;
        this.subscriptions = subscriptions;
        this.calendar = calendar;
        this.recommendationProperties = recommendationProperties;
        this.automationProperties = automationProperties;
    }

    /**
     * 기본 30초마다 불려 오늘 할 일을 순서대로 확인해요.
     * 받는 것·돌려주는 것: 없음.
     */
    @Scheduled(fixedDelayString = "${closing.automation.poll-interval:30s}")
    public synchronized void poll() {
        Instant now = calendar.now();
        LocalDate today = now.atZone(ClosingTradingCalendar.ZONE).toLocalDate();
        LocalTime time = now.atZone(ClosingTradingCalendar.ZONE).toLocalTime();
        // 구독 동기화는 휴장일에도 돌려 필요 없어진 구독을 정리해요.
        syncSubscriptionsIfDue(today, time, now, false);
        if (!calendar.isTradingDay(today))
            return;
        // 입력 마감(+유예) 뒤, 진입 마감 전에만 공식 평가를 만들어요. 새로 만들었으면 바로 구독을 맞춰요.
        if (!time.isBefore(recommendationProperties.featureFreezeAt().plus(recommendationProperties.candleFinalizationGrace()))
                && time.isBefore(recommendationProperties.entryDeadline())) {
            if (generate(today))
                syncSubscriptionsIfDue(today, time, now, true);
        }
        // 전날 추천의 성과 추적과 후보 관측은 따로 조건을 보고 실행돼요.
        trackPreviousSession(today, time, now);
        trackCandidateObservations(today, now);
        retryLateObservations(today, time);
    }

    /**
     * 오늘 공식 평가 run이 없으면 만들어요. 같은 요청 키(official-날짜-전략버전)로 이미 있으면 다시 만들지 않아요.
     * 받는 것: 추천 날짜. 돌려주는 것: 이번에 새로 만들었으면 true, 이미 있거나 실패하면 false.
     * ⚠️ 확인 필요: 자동화 limit은 1~30(기본 10)이지만 최종 추천은 LIMITED_MODE_MAX_CANDIDATES=5로 다시 잘려요.
     */
    private boolean generate(LocalDate date) {
        String key = officialKey(date);
        if (runs.findByRequestKey(key).isPresent())
            return false;
        try {
            recommendations.generate(date, automationProperties.limit(), automationProperties.minimumOpportunity(),
                    automationProperties.maximumRisk(), key);
            return true;
        } catch (RuntimeException error) {
            log.warn("Official closing recommendation attempt failed for {}: {}", date, rootMessage(error));
            return false;
        }
    }

    /**
     * 09:00 이후 5분마다 전 거래일 공식 run의 다음날 성과를 갱신해요.
     * 받는 것: 오늘 날짜, 현재 시각(로컬·Instant). 돌려주는 것: 없음. 실패는 로그만 남겨요.
     */
    private void trackPreviousSession(LocalDate today, LocalTime time, Instant now) {
        if (time.isBefore(LocalTime.of(9, 0)))
            return;
        long bucket = now.getEpochSecond() / 300;
        if (bucket == lastPerformanceBucket)
            return;
        lastPerformanceBucket = bucket;
        LocalDate recommendationDate = calendar.previousTradingDay(today);
        ClosingRecommendationRun run = officialRun(recommendationDate);
        if (run == null)
            return;
        try {
            performances.track(recommendationDate, automationProperties.targetRate(),
                    automationProperties.stopRate(), run.getId());
        } catch (RuntimeException error) {
            log.warn("Overnight performance tracking failed for {}: {}", recommendationDate, rootMessage(error));
        }
    }

    /**
     * Once per trading day after the close: the previous official run plus any older run whose
     * observed session has passed but still has rows that were never re-evaluated. Missing data stays missing.
     * 하는 일: 장 마감 10분 뒤 하루 한 번, 관측 백필 후 후보 관측을 다시 평가해요.
     * 받는 것: 오늘 날짜, 현재 시각. 돌려주는 것: 없음. run별 실패는 로그만 남겨요.
     */
    private void trackCandidateObservations(LocalDate today, Instant now) {
        if (today.equals(lastObservationDate) || now.isBefore(calendar.close(today).plus(OBSERVATION_DELAY)))
            return;
        lastObservationDate = today;
        ClosingRecommendationRun previous = officialRun(calendar.previousTradingDay(today));
        // ADR-010: fill missing next-session bars of the previous run's precision candidates before judging them.
        ObservationBackfillService backfill = observationBackfill == null ? null : observationBackfill.getIfAvailable();
        if (backfill != null) {
            // 보완 대상(정밀 후보)은 관측 행에서 찾는데, 그 행은 관측 평가가 처음 만들어요. 그래서 전 거래일 run을 먼저
            // 한 번 평가해 행을 만든 뒤 보완하고, 아래에서 다시 평가해요(2026-10-15 15:40 첫 실행이 stocks=0으로 끝남).
            if (previous != null)
                trackObservations(previous.getId());
            backfill.fillTodayLogged();
        } else
            log.warn("Observation backfill unavailable: next-session bars of unsubscribed candidates stay missing");
        // 처리: 아직 평가가 끝나지 않은 과거 run과 전 거래일 공식 run을 모아 각각 관측을 갱신해요.
        Set<Long> runIds = new TreeSet<>(observationRows
                .findRunIdsByStatusInAndExpectedSessionDateOnOrBefore(AWAITING_EVALUATION, today));
        if (previous != null)
            runIds.add(previous.getId());
        for (Long runId : runIds)
            trackObservations(runId);
    }

    /**
     * 하는 일: 거래일 18:00 이후 하루 한 번, 전일 공식 run 정밀 후보의 분봉 보완을 다시 하고 관측을 다시 평가해요.
     * 15:40 보완 때 KIS가 아직 0으로 주던 15:30 종가 단일가 체결량(거래량 0인 15:25 봉)과 남은 빈칸만 채워요.
     * 실시간으로 확정된 봉은 덮어쓰지 않아요(ObservationBackfillService 규칙). 받는 것: 오늘 날짜, 현재 KST 시각.
     */
    private void retryLateObservations(LocalDate today, LocalTime time) {
        if (today.equals(lastLateObservationDate) || time.isBefore(LATE_OBSERVATION_AT))
            return;
        lastLateObservationDate = today;
        ObservationBackfillService backfill = observationBackfill == null ? null : observationBackfill.getIfAvailable();
        ClosingRecommendationRun previous = officialRun(calendar.previousTradingDay(today));
        if (backfill == null || previous == null)
            return;
        backfill.fillTodayLogged();
        trackObservations(previous.getId());
    }

    /** 하는 일: run 하나의 후보 관측을 평가해요. 실패해도 로그만 남기고 다음 run으로 넘어가요. 받는 것: run id. */
    private void trackObservations(Long runId) {
        try {
            observations.track(runId, automationProperties.targetRate(), automationProperties.stopRate());
        } catch (RuntimeException error) {
            log.warn("Candidate observation tracking failed for run {}: {}", runId, rootMessage(error));
        }
    }

    /**
     * 공식 추천 종목과 관측 후보 종목을 실시간 구독(OVERNIGHT)으로 맞춰요. 5분에 한 번, 또는 force면 바로 해요.
     * 받는 것: 오늘 날짜, 로컬 시각, 현재 시각, 강제 실행 여부. 돌려주는 것: 없음.
     */
    private void syncSubscriptionsIfDue(LocalDate today, LocalTime time, Instant now, boolean force) {
        long bucket = now.getEpochSecond() / 300;
        if (!force && bucket == lastSubscriptionBucket)
            return;
        lastSubscriptionBucket = bucket;
        // Official recommendations first; observation candidates only take slots that are still free.
        Set<String> desired = new LinkedHashSet<>();
        Set<String> observed = new LinkedHashSet<>();
        List<ClosingRecommendationRun> protectedRuns = new java.util.ArrayList<>();
        // Keep the previous run's codes through the random-end closing auction print (15:30:00..15:31:00):
        // that print is the session's final 5M bar for next-day performance.
        if (!calendar.isTradingDay(today) || !now.isAfter(calendar.close(today)
                .plus(FiveMinuteCandleAggregator.CLOSING_AUCTION_WINDOW)))
            protectedRuns.add(officialRun(calendar.previousTradingDay(today)));
        protectedRuns.add(officialRun(today));
        protectedRuns.removeIf(java.util.Objects::isNull);
        // 보호할 run의 SELECTED 종목은 필수로, SELECTED/WATCH 관측 후보는 선택으로 모아요.
        for (ClosingRecommendationRun run : protectedRuns)
            desired.addAll(repository.findStockCodesByRunId(run.getId()));
        for (ClosingRecommendationRun run : protectedRuns)
            addObservationCodes(observed, run);
        observed.removeAll(desired);

        // 처리 1: 필수 종목은 슬롯이 없더라도 등록을 시도해요.
        for (String code : desired) {
            try {
                subscriptions.add(code, RealtimeSubscriptionRegistry.Source.OVERNIGHT);
            } catch (IllegalStateException error) {
                log.warn("Unable to protect overnight subscription {}: {}", code, error.getMessage());
            }
        }
        // 처리 2: 관측 후보는 남는 슬롯이 있을 때만 등록해요. 그래서 관측 표본은 슬롯 여유에 따라 골라져요.
        int skipped = 0;
        for (String code : observed) {
            if (!subscriptions.all().contains(code) && subscriptions.remaining() == 0) { skipped++; continue; }
            try {
                subscriptions.add(code, RealtimeSubscriptionRegistry.Source.OVERNIGHT);
                desired.add(code);
            } catch (IllegalStateException error) {
                skipped++;
            }
        }
        if (skipped > 0)
            log.info("Closing observation subscriptions skipped for {} candidates: no free realtime slots", skipped);
        // 정리: 지난번에 잡았지만 이번에 필요 없는 종목은 OVERNIGHT 구독을 해제하고, 보호 목록을 바꿔요.
        for (String code : new HashSet<>(protectedCodes)) {
            if (!desired.contains(code))
                subscriptions.remove(code, RealtimeSubscriptionRegistry.Source.OVERNIGHT);
        }
        protectedCodes.clear();
        protectedCodes.addAll(desired);
    }

    /**
     * run에서 관측용으로 구독할 후보 종목을 설정 개수만큼 더해요.
     * 받는 것: 담을 집합, run. 돌려주는 것: 없음. 실패는 로그만 남겨요.
     */
    private void addObservationCodes(Set<String> target, ClosingRecommendationRun run) {
        int limit = automationProperties.observationSubscriptionLimit();
        if (limit <= 0)
            return;
        try {
            target.addAll(observations.subscriptionCandidates(run, limit));
        } catch (RuntimeException error) {
            log.warn("Closing observation subscription candidates unavailable for run {}: {}", run.getId(), rootMessage(error));
        }
    }

    /**
     * 날짜별 공식 run의 요청 키를 만들어요.
     * 받는 것: 날짜. 돌려주는 것: "official-날짜-전략버전".
     */
    private String officialKey(LocalDate date) {
        return "official-" + date + "-" + ClosingRecommendation.STRATEGY_VERSION;
    }

    /**
     * 그 날짜의 공식 run을 찾아요. 현재 버전 키가 없으면 그날의 최신 FORWARD run을 써요.
     * 받는 것: 날짜. 돌려주는 것: run, 없으면 null.
     */
    private ClosingRecommendationRun officialRun(LocalDate date) {
        return runs.findByRequestKey(officialKey(date)).or(() ->
                runs.findFirstByRecommendationDateAndExecutionModeOrderByIdDesc(date, "FORWARD")).orElse(null);
    }

    /**
     * 예외의 가장 안쪽 원인 메시지를 꺼내요(로그용).
     * 받는 것: 예외. 돌려주는 것: 원인 메시지, 없으면 예외 클래스 이름.
     */
    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null)
            current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
