package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.closing.config.ClosingAutomationProperties;
import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRunRepository;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRepository;
import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "closing.automation", name = "enabled", havingValue = "true")
public class ClosingRecommendationScheduler {
    private static final Logger log = LoggerFactory.getLogger(ClosingRecommendationScheduler.class);

    private final ClosingRecommendationService recommendations;
    private final OvernightPerformanceService performances;
    private final ClosingRecommendationRepository repository;
    private final ClosingRecommendationRunRepository runs;
    private final RealtimeSubscriptionRegistry subscriptions;
    private final ClosingTradingCalendar calendar;
    private final ClosingRecommendationProperties recommendationProperties;
    private final ClosingAutomationProperties automationProperties;
    private final Set<String> protectedCodes = new HashSet<>();
    private long lastSubscriptionBucket = Long.MIN_VALUE;
    private long lastPerformanceBucket = Long.MIN_VALUE;

    public ClosingRecommendationScheduler(ClosingRecommendationService recommendations,
            OvernightPerformanceService performances, ClosingRecommendationRepository repository,
            ClosingRecommendationRunRepository runs, RealtimeSubscriptionRegistry subscriptions,
            ClosingTradingCalendar calendar,
            ClosingRecommendationProperties recommendationProperties,
            ClosingAutomationProperties automationProperties) {
        this.recommendations = recommendations;
        this.performances = performances;
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
        if (!time.isBefore(recommendationProperties.featureFreezeAt())
                && !time.isAfter(recommendationProperties.entryDeadline())) {
            if (generate(today))
                syncSubscriptionsIfDue(today, time, now, true);
        }
        trackPreviousSession(today, time, now);
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
        ClosingRecommendationRun run = runs.findByRequestKey(officialKey(recommendationDate)).orElse(null);
        if (run == null)
            return;
        try {
            performances.track(recommendationDate, automationProperties.targetRate(),
                    automationProperties.stopRate(), run.getId());
        } catch (RuntimeException error) {
            log.warn("Overnight performance tracking failed for {}: {}", recommendationDate, rootMessage(error));
        }
    }

    private void syncSubscriptionsIfDue(LocalDate today, LocalTime time, Instant now, boolean force) {
        long bucket = now.getEpochSecond() / 300;
        if (!force && bucket == lastSubscriptionBucket)
            return;
        lastSubscriptionBucket = bucket;
        Set<String> desired = new HashSet<>();
        if (!calendar.isTradingDay(today) || !time.isAfter(LocalTime.of(15, 30)))
            addCodes(desired, calendar.previousTradingDay(today));
        addCodes(desired, today);

        for (String code : desired) {
            try {
                subscriptions.add(code, RealtimeSubscriptionRegistry.Source.OVERNIGHT);
            } catch (IllegalStateException error) {
                log.warn("Unable to protect overnight subscription {}: {}", code, error.getMessage());
            }
        }
        for (String code : new HashSet<>(protectedCodes)) {
            if (!desired.contains(code))
                subscriptions.remove(code, RealtimeSubscriptionRegistry.Source.OVERNIGHT);
        }
        protectedCodes.clear();
        protectedCodes.addAll(desired);
    }

    private void addCodes(Set<String> target, LocalDate date) {
        ClosingRecommendationRun run = runs.findByRequestKey(officialKey(date)).orElse(null);
        if (run == null)
            return;
        repository.findByRunIdOrderByRankAsc(run.getId()).stream()
                .map(row -> row.getStock().getStockCode())
                .forEach(target::add);
    }

    private String officialKey(LocalDate date) {
        return "official-" + date + "-" + ClosingRecommendation.STRATEGY_VERSION;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null)
            current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
