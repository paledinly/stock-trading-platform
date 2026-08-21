package com.sunmo.stockplatform.market.application;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RealtimeDiagnostics {
    public enum SubscriptionState { QUEUED, REQUESTED, SUBSCRIBED, FAILED }

    public record SubscriptionStatus(String stockCode, SubscriptionState state, String message, Instant updatedAt) {}

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
            long subscriptionRequests,
            long subscriptionSuccesses,
            long subscriptionFailures,
            Map<String, SubscriptionStatus> subscriptions
    ) {}

    private final Map<String, SubscriptionStatus> subscriptions = new ConcurrentSkipListMap<>();
    private final AtomicLong receivedFrames = new AtomicLong();
    private final AtomicLong receivedTicks = new AtomicLong();
    private final AtomicLong parseErrors = new AtomicLong();
    private final AtomicLong subscriptionRequests = new AtomicLong();
    private final AtomicLong subscriptionSuccesses = new AtomicLong();
    private final AtomicLong subscriptionFailures = new AtomicLong();
    private volatile boolean connected;
    private volatile Instant connectedAt;
    private volatile Instant disconnectedAt;
    private volatile Instant lastMessageAt;
    private volatile Instant lastPingAt;
    private volatile Instant lastTickAt;

    public void connected() {
        connected = true;
        connectedAt = Instant.now();
    }

    public void disconnected() {
        connected = false;
        disconnectedAt = Instant.now();
        subscriptions.replaceAll((code, status) ->
                new SubscriptionStatus(code, SubscriptionState.QUEUED, "waiting for reconnect", Instant.now()));
    }

    public void messageReceived() {
        receivedFrames.incrementAndGet();
        lastMessageAt = Instant.now();
    }

    public void pingReceived() {
        lastPingAt = Instant.now();
    }

    public void queued(String stockCode) {
        subscriptions.put(stockCode,
                new SubscriptionStatus(stockCode, SubscriptionState.QUEUED, "waiting for connection", Instant.now()));
    }

    public void subscriptionRequested(String stockCode) {
        subscriptionRequests.incrementAndGet();
        subscriptions.put(stockCode,
                new SubscriptionStatus(stockCode, SubscriptionState.REQUESTED, "waiting for KIS response", Instant.now()));
    }

    public void subscriptionAcknowledged(String stockCode, boolean success, String message) {
        if (success) subscriptionSuccesses.incrementAndGet();
        else subscriptionFailures.incrementAndGet();
        subscriptions.put(stockCode, new SubscriptionStatus(stockCode,
                success ? SubscriptionState.SUBSCRIBED : SubscriptionState.FAILED, message, Instant.now()));
    }

    public void ticksReceived(int count) {
        receivedTicks.addAndGet(count);
        lastTickAt = Instant.now();
    }

    public void parseFailed() {
        parseErrors.incrementAndGet();
    }

    public Snapshot snapshot() {
        return new Snapshot(connected, connectedAt, disconnectedAt, lastMessageAt, lastPingAt, lastTickAt,
                receivedFrames.get(), receivedTicks.get(), parseErrors.get(), subscriptionRequests.get(),
                subscriptionSuccesses.get(), subscriptionFailures.get(), Map.copyOf(subscriptions));
    }
}
