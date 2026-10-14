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

@Component
@ConditionalOnProperty(prefix = "closing.automation", name = "enabled", havingValue = "true")
public class ClosingRecommendationScheduler {
    private static final Logger log = LoggerFactory.getLogger(ClosingRecommendationScheduler.class);
    /** Lets the last 5-minute bar finalize before unselected candidates are judged. */
    private static final Duration OBSERVATION_DELAY = Duration.ofMinutes(10);
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
    private final Set<String> protectedCodes = new HashSet<>();
    private org.springframework.beans.factory.ObjectProvider<ObservationBackfillService> observationBackfill;

    @org.springframework.beans.factory.annotation.Autowired
    void setObservationBackfill(org.springframework.beans.factory.ObjectProvider<ObservationBackfillService> value) {
        this.observationBackfill = value;
    }
    private long lastSubscriptionBucket = Long.MIN_VALUE;
    private long lastPerformanceBucket = Long.MIN_VALUE;
    private LocalDate lastObservationDate;

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

    @Scheduled(fixedDelayString = "${closing.automation.poll-interval:30s}")
    public synchronized void poll() {
        Instant now = calendar.now();
        LocalDate today = now.atZone(ClosingTradingCalendar.ZONE).toLocalDate();
        LocalTime time = now.atZone(ClosingTradingCalendar.ZONE).toLocalTime();
        syncSubscriptionsIfDue(today, time, now, false);
        if (!calendar.isTradingDay(today))
            return;
        if (!time.isBefore(recommendationProperties.featureFreezeAt().plus(recommendationProperties.candleFinalizationGrace()))
                && time.isBefore(recommendationProperties.entryDeadline())) {
            if (generate(today))
                syncSubscriptionsIfDue(today, time, now, true);
        }
        trackPreviousSession(today, time, now);
        trackCandidateObservations(today, now);
    }

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
     */
    private void trackCandidateObservations(LocalDate today, Instant now) {
        if (today.equals(lastObservationDate) || now.isBefore(calendar.close(today).plus(OBSERVATION_DELAY)))
            return;
        lastObservationDate = today;
        // ADR-010: fill missing next-session bars of the previous run's precision candidates before judging them.
        ObservationBackfillService backfill = observationBackfill == null ? null : observationBackfill.getIfAvailable();
        if (backfill != null)
            backfill.fillTodayLogged();
        else
            log.warn("Observation backfill unavailable: next-session bars of unsubscribed candidates stay missing");
        Set<Long> runIds = new TreeSet<>(observationRows
                .findRunIdsByStatusInAndExpectedSessionDateOnOrBefore(AWAITING_EVALUATION, today));
        ClosingRecommendationRun previous = officialRun(calendar.previousTradingDay(today));
        if (previous != null)
            runIds.add(previous.getId());
        for (Long runId : runIds) {
            try {
                observations.track(runId, automationProperties.targetRate(), automationProperties.stopRate());
            } catch (RuntimeException error) {
                log.warn("Candidate observation tracking failed for run {}: {}", runId, rootMessage(error));
            }
        }
    }

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
        for (ClosingRecommendationRun run : protectedRuns)
            desired.addAll(repository.findStockCodesByRunId(run.getId()));
        for (ClosingRecommendationRun run : protectedRuns)
            addObservationCodes(observed, run);
        observed.removeAll(desired);

        for (String code : desired) {
            try {
                subscriptions.add(code, RealtimeSubscriptionRegistry.Source.OVERNIGHT);
            } catch (IllegalStateException error) {
                log.warn("Unable to protect overnight subscription {}: {}", code, error.getMessage());
            }
        }
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
        for (String code : new HashSet<>(protectedCodes)) {
            if (!desired.contains(code))
                subscriptions.remove(code, RealtimeSubscriptionRegistry.Source.OVERNIGHT);
        }
        protectedCodes.clear();
        protectedCodes.addAll(desired);
    }

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

    private String officialKey(LocalDate date) {
        return "official-" + date + "-" + ClosingRecommendation.STRATEGY_VERSION;
    }

    private ClosingRecommendationRun officialRun(LocalDate date) {
        return runs.findByRequestKey(officialKey(date)).or(() ->
                runs.findFirstByRecommendationDateAndExecutionModeOrderByIdDesc(date, "FORWARD")).orElse(null);
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null)
            current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
