package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Samples realtime health just before the feature freeze and after the entry deadline, then records the
 * daily completeness row at close + 15m on trading days. Read-only with respect to the strategy.
 */
@Component
@ConditionalOnProperty(prefix = "closing.automation", name = "enabled", havingValue = "true")
public class ClosingDailyHealthRecorder {
    private static final Logger log = LoggerFactory.getLogger(ClosingDailyHealthRecorder.class);
    /** The pre-freeze sample is the last poll inside this lead before the freeze time. */
    static final Duration SAMPLE_LEAD = Duration.ofMinutes(1);

    private record Sample(Instant at, boolean connected, long dropped, Instant disconnectedAt) { }

    private final ClosingDailyHealthService health;
    private final RealtimeDiagnostics diagnostics;
    private final ClosingTradingCalendar calendar;
    private final ClosingRecommendationProperties policy;
    private LocalDate sampleDate;
    private Sample beforeFreeze;
    private Sample afterDeadline;
    private LocalDate recordedDate;

    public ClosingDailyHealthRecorder(ClosingDailyHealthService health, RealtimeDiagnostics diagnostics,
            ClosingTradingCalendar calendar, ClosingRecommendationProperties policy) {
        this.health = health;
        this.diagnostics = diagnostics;
        this.calendar = calendar;
        this.policy = policy;
    }

    @Scheduled(fixedDelayString = "${closing.automation.poll-interval:30s}")
    public synchronized void poll() {
        Instant now = calendar.now();
        LocalDate today = now.atZone(ClosingTradingCalendar.ZONE).toLocalDate();
        LocalTime time = now.atZone(ClosingTradingCalendar.ZONE).toLocalTime();
        if (!calendar.isTradingDay(today))
            return;
        if (!today.equals(sampleDate)) {
            sampleDate = today;
            beforeFreeze = null;
            afterDeadline = null;
        }
        LocalTime freeze = policy.featureFreezeAt();
        if (!time.isBefore(freeze.minus(SAMPLE_LEAD)) && time.isBefore(freeze))
            beforeFreeze = sample(now);
        else if (beforeFreeze != null && afterDeadline == null && !time.isBefore(policy.entryDeadline()))
            afterDeadline = sample(now);

        if (today.equals(recordedDate) || now.isBefore(calendar.close(today).plus(ClosingDailyHealthService.CHECK_DELAY)))
            return;
        try {
            ClosingDailyHealthService.DailyHealth row = health.recordToday(window());
            recordedDate = today;
            log.info("Closing daily health {}: {} {}", today, row.verdict(), row.reasons());
        } catch (RuntimeException error) {
            log.warn("Closing daily health record failed for {}: {}", today, error.getMessage());
        }
    }

    /** Both samples are needed; a restart or a gap in between leaves the window unobserved. */
    ClosingDailyHealthService.RealtimeWindow window() {
        if (beforeFreeze == null || afterDeadline == null)
            return ClosingDailyHealthService.RealtimeWindow.UNOBSERVED;
        boolean stayedConnected = beforeFreeze.connected() && afterDeadline.connected()
                && (afterDeadline.disconnectedAt() == null || !afterDeadline.disconnectedAt().isAfter(beforeFreeze.at()));
        return new ClosingDailyHealthService.RealtimeWindow(stayedConnected,
                Math.max(0, afterDeadline.dropped() - beforeFreeze.dropped()), beforeFreeze.at(), afterDeadline.at());
    }

    private Sample sample(Instant now) {
        RealtimeDiagnostics.Snapshot snapshot = diagnostics.snapshot();
        return new Sample(now, snapshot.connected(), snapshot.processingDropped(), snapshot.disconnectedAt());
    }
}
