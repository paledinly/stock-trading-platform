package com.sunmo.stockplatform.market.application;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RealtimeDiagnostics {
    public enum SubscriptionState {
        QUEUED, REQUESTED, SUBSCRIBED, FAILED
    }

    public record SubscriptionStatus(String stockCode, SubscriptionState state, String message, Instant updatedAt) {
    }

    public record BackfillStatus(String stockCode, String state, int gaps, int saved, String message,
            Instant updatedAt) {
    }

    public record Snapshot(
            boolean connected,
            Instant connectedAt,
            Instant disconnectedAt,
            Instant lastMessageAt,
            Instant lastPingAt,
            Instant lastTickAt,
            long receivedFrames,
            long receivedTicks,
            long parseErrors,
            long processingErrors,
            long subscriptionRequests,
            long subscriptionSuccesses,
            long subscriptionFailures,
            Instant lastCandleAt,
            Instant lastScannerEvaluationAt,
            long detections,
            int pendingPerformances,
            Instant lastPerformanceFlushAt,
            int lastPerformanceFlushSize,
            Instant lastFeatureAt,
            long featureSnapshots,
            int featureTrackedStocks,
            Map<String, Integer> candleGaps,
            Map<String, BackfillStatus> backfills,
            java.util.List<String> recentErrors,
            Map<String, SubscriptionStatus> subscriptions,
            Map<String, Timing> timings, int processingQueued, long processingDropped,
            long outsideSessionTicks, Instant lastOutsideSessionSourceAt,
            long closingAuctionTicks, Instant lastClosingAuctionSourceAt,
            long scannerCooldownFallbacks, Instant lastScannerCooldownFallbackAt) {
    }

    /** Averages stay clamped at zero; minMs/negativeCount keep raw values so host-vs-source clock skew stays visible. */
    public record Timing(long count, double averageMs, double maxMs, double lastMs, double minMs, long negativeCount) {}
    private static class Samples {
        long count, total, max, last, min = Long.MAX_VALUE, negatives;
        synchronized void add(long raw) {
            long nanos = Math.max(0, raw);
            count++; total += nanos; max = Math.max(max, nanos); last = nanos;
            min = Math.min(min, raw); if (raw < 0) negatives++;
        }
        synchronized Timing snapshot() {
            return new Timing(count, count == 0 ? 0 : total / (double) count / 1e6, max / 1e6, last / 1e6,
                    count == 0 ? 0 : min / 1e6, negatives);
        }
    }
    private final Map<String, Samples> timings = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile java.util.function.IntSupplier queueSize = () -> 0;
    private final AtomicLong processingDropped = new AtomicLong();
    public void timing(String stage, long nanos) { timings.computeIfAbsent(stage, key -> new Samples()).add(nanos); }
    public void processingQueue(java.util.function.IntSupplier size) { queueSize = size; }
    public void droppedTick() { processingDropped.incrementAndGet(); }
    private final AtomicLong outsideSessionTicks = new AtomicLong();
    private final AtomicLong closingAuctionTicks = new AtomicLong();
    private volatile Instant lastOutsideSessionSourceAt;
    private volatile Instant lastClosingAuctionSourceAt;
    /** Parsed ticks rejected by the session window (source time), so a missing closing print is observable. */
    public void outsideSessionTick(Instant sourceAt) { outsideSessionTicks.incrementAndGet(); lastOutsideSessionSourceAt = sourceAt; }
    /** Accepted closing auction prints stamped after 15:30:00 (random end). */
    public void closingAuctionTick(Instant sourceAt) { closingAuctionTicks.incrementAndGet(); lastClosingAuctionSourceAt = sourceAt; }
    private final AtomicLong scannerCooldownFallbacks = new AtomicLong();
    private volatile Instant lastScannerCooldownFallbackAt;
    /** Scanner cooldown fell back to process memory; the error list gets at most one entry per minute. */
    public void scannerCooldownFallback(String reason) {
        scannerCooldownFallbacks.incrementAndGet();
        Instant now = Instant.now();
        Instant previous = lastScannerCooldownFallbackAt;
        lastScannerCooldownFallbackAt = now;
        if (previous == null || !now.isBefore(previous.plus(Duration.ofMinutes(1))))
            error("Scanner cooldown fallback: " + reason);
    }

    private final Map<String, SubscriptionStatus> subscriptions = new ConcurrentSkipListMap<>();
    private final AtomicLong receivedFrames = new AtomicLong();
    private final AtomicLong receivedTicks = new AtomicLong();
    private final AtomicLong parseErrors = new AtomicLong();
    private final AtomicLong processingErrors = new AtomicLong();
    private final AtomicLong subscriptionRequests = new AtomicLong();
    private final AtomicLong subscriptionSuccesses = new AtomicLong();
    private final AtomicLong subscriptionFailures = new AtomicLong();
    private final AtomicLong detections = new AtomicLong();
    private final Map<String, Integer> candleGaps = new ConcurrentSkipListMap<>();
    private final Map<String, BackfillStatus> backfills = new ConcurrentSkipListMap<>();
    private final java.util.Deque<String> recentErrors = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private volatile boolean connected;
    private volatile Instant connectedAt;
    private volatile Instant disconnectedAt;
    private volatile Instant lastMessageAt;
    private volatile Instant lastPingAt;
    private volatile Instant lastTickAt;
    private volatile Instant lastCandleAt;
    private volatile Instant lastScannerEvaluationAt;
    private volatile int pendingPerformances;
    private volatile Instant lastPerformanceFlushAt;
    private volatile int lastPerformanceFlushSize;
    private final AtomicLong featureSnapshots = new AtomicLong();
    private volatile Instant lastFeatureAt;
    private volatile int featureTrackedStocks;

    public void connected() {
        connected = true;
        connectedAt = Instant.now();
    }

    public void disconnected() {
        connected = false;
        disconnectedAt = Instant.now();
        subscriptions.replaceAll((code, status) -> new SubscriptionStatus(code, SubscriptionState.QUEUED,
                "waiting for reconnect", Instant.now()));
    }

    public void messageReceived() {
        receivedFrames.incrementAndGet();
        lastMessageAt = Instant.now();
    }

    public void pingReceived() {
        lastPingAt = Instant.now();
    }

    /**
     * A repeated registry add or a send racing the connect callback can queue a code that KIS already acknowledged.
     * While connected that must not hide the subscription; a real disconnect resets every code anyway.
     */
    public void queued(String stockCode) {
        subscriptions.compute(stockCode, (code, current) -> connected && current != null
                && current.state() == SubscriptionState.SUBSCRIBED ? current
                : new SubscriptionStatus(code, SubscriptionState.QUEUED, "waiting for connection", Instant.now()));
    }

    public void subscriptionRequested(String stockCode) {
        subscriptionRequests.incrementAndGet();
        subscriptions.put(stockCode,
                new SubscriptionStatus(stockCode, SubscriptionState.REQUESTED, "waiting for KIS response",
                        Instant.now()));
    }

    public void subscriptionAcknowledged(String stockCode, boolean success, String message) {
        if (success)
            subscriptionSuccesses.incrementAndGet();
        else
            subscriptionFailures.incrementAndGet();
        subscriptions.put(stockCode, new SubscriptionStatus(stockCode,
                success ? SubscriptionState.SUBSCRIBED : SubscriptionState.FAILED, message, Instant.now()));
    }

    public void ticksReceived(int count) {
        receivedTicks.addAndGet(count);
        lastTickAt = Instant.now();
    }

    public void parseFailed(String reason) {
        parseErrors.incrementAndGet();
        error("KIS realtime parse failed: " + reason);
    }

    public void processingFailed(String stockCode, String reason) {
        processingErrors.incrementAndGet();
        error("Realtime tick processing failed for " + stockCode + ": " + reason);
    }

    public boolean isConnectionStale(Instant now, Duration timeout) {
        Instant latest = lastMessageAt == null ? connectedAt : lastMessageAt;
        if (connectedAt != null && (latest == null || connectedAt.isAfter(latest))) latest = connectedAt;
        return connected && latest != null && latest.plus(timeout).isBefore(now);
    }

    public Instant lastTickAt() {
        return lastTickAt;
    }

    public void subscriptionRemoved(String stockCode, boolean success, String message) {
        if (success)
            subscriptions.remove(stockCode);
        else
            subscriptionAcknowledged(stockCode, false, message);
    }

    public void candlePersisted(Instant startTime) {
        lastCandleAt = startTime;
    }

    public void scannerEvaluated() {
        lastScannerEvaluationAt = Instant.now();
    }

    public void detectionCreated() {
        detections.incrementAndGet();
    }

    public void pendingPerformances(int count) {
        pendingPerformances = count;
    }

    public void performanceFlushed(int count) {
        lastPerformanceFlushAt = Instant.now();
        lastPerformanceFlushSize = count;
    }

    public void featureSnapshot(int trackedStocks) {
        lastFeatureAt = Instant.now();
        featureTrackedStocks = trackedStocks;
        featureSnapshots.incrementAndGet();
    }

    public void candleGaps(String stockCode, int count) {
        candleGaps.put(stockCode, count);
    }

    public void backfillStarted(String stockCode, int gaps) {
        backfills.put(stockCode, new BackfillStatus(stockCode, "RUNNING", gaps, 0, null, Instant.now()));
    }

    public void backfillSucceeded(String stockCode, int gaps, int saved) {
        backfills.put(stockCode, new BackfillStatus(stockCode, "COMPLETED", gaps, saved, null, Instant.now()));
        candleGaps.put(stockCode, Math.max(0, gaps - saved));
    }

    public void backfillFailed(String stockCode, int gaps, String message) {
        backfills.put(stockCode, new BackfillStatus(stockCode, "FAILED", gaps, 0, message, Instant.now()));
        error("Backfill " + stockCode + ": " + message);
    }

    public void error(String message) {
        recentErrors.addFirst(Instant.now() + " " + message);
        while (recentErrors.size() > 20)
            recentErrors.pollLast();
    }

    public Snapshot snapshot() {
        return new Snapshot(connected, connectedAt, disconnectedAt, lastMessageAt, lastPingAt, lastTickAt,
                receivedFrames.get(), receivedTicks.get(), parseErrors.get(), processingErrors.get(), subscriptionRequests.get(),
                subscriptionSuccesses.get(), subscriptionFailures.get(), lastCandleAt, lastScannerEvaluationAt,
                detections.get(), pendingPerformances, lastPerformanceFlushAt, lastPerformanceFlushSize,
                lastFeatureAt, featureSnapshots.get(), featureTrackedStocks,
                Map.copyOf(candleGaps), Map.copyOf(backfills), java.util.List.copyOf(recentErrors),
                Map.copyOf(subscriptions), timingSnapshot(), queueSize.getAsInt(), processingDropped.get(),
                outsideSessionTicks.get(), lastOutsideSessionSourceAt, closingAuctionTicks.get(), lastClosingAuctionSourceAt,
                scannerCooldownFallbacks.get(), lastScannerCooldownFallbackAt);
    }

    private Map<String, Timing> timingSnapshot() {
        Map<String, Timing> result = new java.util.TreeMap<>();
        timings.forEach((name, samples) -> result.put(name, samples.snapshot()));
        return Map.copyOf(result);
    }
}
