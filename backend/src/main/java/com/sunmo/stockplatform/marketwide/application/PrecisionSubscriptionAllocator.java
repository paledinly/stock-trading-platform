package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.market.config.PrecisionSubscriptionProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PrecisionSubscriptionAllocator {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private final RealtimeSubscriptionRegistry registry;
    private final PrecisionSubscriptionProperties properties;
    private final PrecisionSubscriptionHistory history;
    private final Map<String, Allocation> allocations = new ConcurrentHashMap<>();
    private final Map<String, String> pendingReplacements = new ConcurrentHashMap<>();

    public PrecisionSubscriptionAllocator(RealtimeSubscriptionRegistry registry,
            PrecisionSubscriptionProperties properties, PrecisionSubscriptionHistory history) {
        this.registry = registry;
        this.properties = properties;
        this.history = history;
    }

    @PostConstruct
    public void initialize() {
        registry.onAcknowledged(this::acknowledged);
    }

    public synchronized Snapshot reconcile(List<Candidate> incoming) {
        return reconcile(incoming, Instant.now());
    }

    public synchronized Snapshot snapshot() {
        return snapshot(properties.enabled() ? (frozen(Instant.now()) ? "FROZEN" : "ACTIVE") : "DISABLED",
                capacity(), Instant.now());
    }

    synchronized Snapshot reconcile(List<Candidate> incoming, Instant now) {
        if (!properties.enabled())
            return snapshot("DISABLED", capacity(), now);

        Map<String, Candidate> candidates = new LinkedHashMap<>();
        incoming.stream().sorted(Comparator.comparing(Candidate::score).reversed())
                .forEach(candidate -> candidates.putIfAbsent(candidate.stockCode(), candidate));
        candidates.forEach((code, candidate) -> allocations.compute(code, (ignored, current) ->
                current == null ? null : current.withScore(candidate.score(), now)));

        int capacity = capacity();
        trimExcess(capacity, now);
        fill(candidates.values(), capacity, now);
        replace(candidates.values(), capacity, now);
        return snapshot(frozen(now) ? "FROZEN" : "ACTIVE", capacity, now);
    }

    private void fill(Collection<Candidate> candidates, int capacity, Instant now) {
        for (Candidate candidate : candidates) {
            if (precisionCount() >= capacity)
                return;
            if (hasPrecision(candidate.stockCode()))
                continue;
            add(candidate, now, null);
        }
    }

    private void replace(Collection<Candidate> candidates, int capacity, Instant now) {
        if (capacity == 0 || precisionCount() < capacity || frozen(now) || !pendingReplacements.isEmpty())
            return;
        Allocation weakest = removable(now).orElse(null);
        if (weakest == null)
            return;
        Candidate replacement = candidates.stream()
                .filter(candidate -> !hasPrecision(candidate.stockCode()))
                .filter(candidate -> candidate.score().subtract(weakest.score())
                        .compareTo(properties.replaceMargin()) >= 0)
                .findFirst().orElse(null);
        if (replacement == null)
            return;
        add(replacement, now, weakest.stockCode());
    }

    private void add(Candidate candidate, Instant now, String replaceCode) {
        boolean alreadySubscribed = registry.all().contains(candidate.stockCode());
        try {
            registry.add(candidate.stockCode(), RealtimeSubscriptionRegistry.Source.PRECISION);
            recordHistory(() -> history.requested(candidate.stockCode(), now, alreadySubscribed));
            allocations.put(candidate.stockCode(), new Allocation(candidate.stockCode(), candidate.score(), now, now));
            if (replaceCode == null)
                return;
            if (alreadySubscribed) {
                remove(replaceCode);
            } else {
                pendingReplacements.put(candidate.stockCode(), replaceCode);
            }
        } catch (IllegalStateException ignored) {
            allocations.remove(candidate.stockCode());
        }
    }

    private void trimExcess(int capacity, Instant now) {
        while (precisionCount() > capacity) {
            Allocation weakest = removable(now).orElse(null);
            if (weakest == null)
                return;
            remove(weakest.stockCode());
        }
    }

    private Optional<Allocation> removable(Instant now) {
        return allocations.values().stream()
                .filter(allocation -> !pendingReplacements.containsKey(allocation.stockCode()))
                .filter(allocation -> !pendingReplacements.containsValue(allocation.stockCode()))
                .filter(allocation -> Duration.between(allocation.addedAt(), now).compareTo(properties.minHold()) >= 0)
                .min(Comparator.comparing(Allocation::score).thenComparing(Allocation::addedAt));
    }

    private synchronized void acknowledged(RealtimeSubscriptionRegistry.Acknowledgement acknowledgement) {
        if (!acknowledgement.subscribing())
            return;
        String oldCode = pendingReplacements.remove(acknowledgement.stockCode());
        if (acknowledgement.success()) {
            recordHistory(() -> history.activated(acknowledgement.stockCode(), Instant.now()));
            if (oldCode != null)
                remove(oldCode);
            return;
        }
        recordHistory(() -> history.rejected(acknowledgement.stockCode(), Instant.now(), acknowledgement.message()));
        registry.remove(acknowledgement.stockCode(), RealtimeSubscriptionRegistry.Source.PRECISION);
        allocations.remove(acknowledgement.stockCode());
    }

    private void remove(String stockCode) {
        registry.remove(stockCode, RealtimeSubscriptionRegistry.Source.PRECISION);
        allocations.remove(stockCode);
        recordHistory(() -> history.ended(stockCode, Instant.now(), "ALLOCATOR_REMOVED"));
    }

    private void recordHistory(Runnable action) {
        try { action.run(); } catch (RuntimeException ignored) { /* metrics failure must not interrupt subscriptions */ }
    }

    private int capacity() {
        long protectedCodes = registry.entries().values().stream()
                .filter(sources -> sources.stream().anyMatch(source -> source != RealtimeSubscriptionRegistry.Source.PRECISION))
                .count();
        return Math.min(properties.maxSubscriptions(),
                Math.max(0, registry.limit() - properties.reserve() - Math.toIntExact(protectedCodes)));
    }

    private int precisionCount() {
        return (int) registry.entries().values().stream()
                .filter(sources -> sources.contains(RealtimeSubscriptionRegistry.Source.PRECISION)).count();
    }

    private boolean hasPrecision(String code) {
        return registry.entries().getOrDefault(code, Set.of())
                .contains(RealtimeSubscriptionRegistry.Source.PRECISION);
    }

    private boolean frozen(Instant now) {
        return !allocations.isEmpty() && !now.atZone(MARKET_ZONE).toLocalTime().isBefore(properties.freezeAt());
    }

    private Snapshot snapshot(String state, int capacity, Instant now) {
        List<AllocationView> active = allocations.values().stream()
                .sorted(Comparator.comparing(Allocation::score).reversed())
                .map(value -> new AllocationView(value.stockCode(), value.score(), value.addedAt(), value.lastSeenAt(),
                        pendingReplacements.containsKey(value.stockCode())))
                .toList();
        return new Snapshot(state, now, capacity, active.size(), registry.remaining(), properties.reserve(), active);
    }

    public record Candidate(String stockCode, BigDecimal score) {
    }

    public record Snapshot(String state, Instant evaluatedAt, int capacity, int activeCount, int remainingSlots,
            int reservedSlots, List<AllocationView> allocations) {
    }

    public record AllocationView(String stockCode, BigDecimal score, Instant addedAt, Instant lastSeenAt,
            boolean awaitingAcknowledgement) {
    }

    private record Allocation(String stockCode, BigDecimal score, Instant addedAt, Instant lastSeenAt) {
        private Allocation withScore(BigDecimal score, Instant seenAt) {
            return new Allocation(stockCode, score, addedAt, seenAt);
        }
    }
}
