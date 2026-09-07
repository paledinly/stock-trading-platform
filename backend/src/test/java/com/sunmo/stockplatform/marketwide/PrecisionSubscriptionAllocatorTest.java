package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.market.config.PrecisionSubscriptionProperties;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrecisionSubscriptionAllocatorTest {
    @Test
    void preservesProtectedSourcesAndFillsOnlyCalculatedCapacity() {
        RealtimeSubscriptionRegistry registry = registry(5);
        registry.add("005930", RealtimeSubscriptionRegistry.Source.WATCHLIST);
        registry.add("000660", RealtimeSubscriptionRegistry.Source.MANUAL);
        PrecisionSubscriptionAllocator allocator = allocator(registry, Duration.ZERO, "10");

        var result = allocator.reconcile(List.of(candidate("A00001", "90"), candidate("A00002", "80"),
                candidate("A00003", "70")));

        assertThat(result.capacity()).isEqualTo(2);
        assertThat(result.activeCount()).isEqualTo(2);
        assertThat(registry.all()).contains("005930", "000660", "A00001", "A00002").doesNotContain("A00003");
        assertThat(registry.remaining()).isEqualTo(1);
    }

    @Test
    void keepsOldCandidateUntilReplacementIsAcknowledged() {
        RealtimeSubscriptionRegistry registry = registry(3);
        PrecisionSubscriptionAllocator allocator = allocator(registry, Duration.ofMinutes(15), "10");
        Instant first = Instant.parse("2026-09-07T04:00:00Z");
        allocator.reconcile(List.of(candidate("A00001", "90"), candidate("A00002", "80")), first);

        var pending = allocator.reconcile(List.of(candidate("A00003", "95"), candidate("A00001", "90")),
                first.plus(Duration.ofMinutes(20)));

        assertThat(pending.allocations()).extracting(PrecisionSubscriptionAllocator.AllocationView::stockCode)
                .contains("A00002", "A00003");
        assertThat(pending.allocations()).filteredOn(PrecisionSubscriptionAllocator.AllocationView::awaitingAcknowledgement)
                .extracting(PrecisionSubscriptionAllocator.AllocationView::stockCode).containsExactly("A00003");
        registry.acknowledge("A00003", true, true, "success");
        assertThat(registry.all()).contains("A00001", "A00003").doesNotContain("A00002");
        assertThat(registry.remaining()).isEqualTo(1);
    }

    @Test
    void removesRejectedPrecisionCandidateWithoutDroppingOldCandidate() {
        RealtimeSubscriptionRegistry registry = registry(3);
        PrecisionSubscriptionAllocator allocator = allocator(registry, Duration.ZERO, "10");
        Instant first = Instant.parse("2026-09-07T04:00:00Z");
        allocator.reconcile(List.of(candidate("A00001", "90"), candidate("A00002", "80")), first);
        allocator.reconcile(List.of(candidate("A00003", "95"), candidate("A00001", "90")),
                first.plusSeconds(1));

        registry.acknowledge("A00003", true, false, "limit exceeded");

        assertThat(registry.all()).contains("A00001", "A00002").doesNotContain("A00003");
    }

    private PrecisionSubscriptionAllocator allocator(RealtimeSubscriptionRegistry registry, Duration minHold,
            String margin) {
        PrecisionSubscriptionAllocator allocator = new PrecisionSubscriptionAllocator(registry,
                new PrecisionSubscriptionProperties(true, 2, 1, minHold, new BigDecimal(margin),
                        LocalTime.of(14, 50)));
        allocator.initialize();
        return allocator;
    }

    private RealtimeSubscriptionRegistry registry(int limit) {
        return new RealtimeSubscriptionRegistry(new RealtimeMarketProperties(true, URI.create("ws://localhost"),
                Duration.ZERO, Duration.ofHours(1), 10, limit));
    }

    private PrecisionSubscriptionAllocator.Candidate candidate(String code, String score) {
        return new PrecisionSubscriptionAllocator.Candidate(code, new BigDecimal(score));
    }
}
