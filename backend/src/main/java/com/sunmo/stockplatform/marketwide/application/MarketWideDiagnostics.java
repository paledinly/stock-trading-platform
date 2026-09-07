package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.BroadScanResponse;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MarketWideDiagnostics {
    private final AtomicLong completedRuns = new AtomicLong();
    private final AtomicLong failedRuns = new AtomicLong();
    private final AtomicLong skippedRuns = new AtomicLong();
    private volatile boolean running;
    private volatile Instant lastStartedAt;
    private volatile Instant lastCompletedAt;
    private volatile Instant lastFailedAt;
    private volatile Instant lastScheduledBucket;
    private volatile String lastSkipReason;
    private volatile String lastError;
    private volatile long lastDurationMillis;
    private volatile int lastScannedCount;
    private volatile int lastCandidateCount;
    private volatile boolean lastFallback;
    private volatile List<Source> rankingSources = List.of();

    public void started(Instant at, Instant bucket) {
        running = true;
        lastStartedAt = at;
        lastScheduledBucket = bucket;
        lastError = null;
    }

    public void completed(Instant at, BroadScanResponse response) {
        running = false;
        lastCompletedAt = at;
        lastDurationMillis = duration(at);
        lastScannedCount = response.scannedCount();
        lastCandidateCount = response.candidateCount();
        lastFallback = response.fallback();
        rankingSources = response.rankingSources().stream()
                .map(source -> new Source(source.type(), source.success(), source.candidateCount(), source.error()))
                .toList();
        completedRuns.incrementAndGet();
    }

    public void failed(Instant at, String error) {
        running = false;
        lastFailedAt = at;
        lastDurationMillis = duration(at);
        lastError = error;
        failedRuns.incrementAndGet();
    }

    public void skipped(String reason, Instant bucket) {
        lastSkipReason = reason;
        if (bucket != null)
            lastScheduledBucket = bucket;
        skippedRuns.incrementAndGet();
    }

    public Snapshot snapshot() {
        return new Snapshot(running, lastStartedAt, lastCompletedAt, lastFailedAt, lastScheduledBucket,
                completedRuns.get(), failedRuns.get(), skippedRuns.get(), lastSkipReason, lastError,
                lastDurationMillis, lastScannedCount, lastCandidateCount, lastFallback, rankingSources);
    }

    private long duration(Instant at) {
        return lastStartedAt == null ? 0 : Math.max(0, Duration.between(lastStartedAt, at).toMillis());
    }

    public record Source(String type, boolean success, int candidateCount, String error) {}
    public record Snapshot(boolean running, Instant lastStartedAt, Instant lastCompletedAt, Instant lastFailedAt,
            Instant lastScheduledBucket, long completedRuns, long failedRuns, long skippedRuns,
            String lastSkipReason, String lastError, long lastDurationMillis, int lastScannedCount,
            int lastCandidateCount, boolean lastFallback, List<Source> rankingSources) {}
}
