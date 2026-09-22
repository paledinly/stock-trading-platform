package com.sunmo.stockplatform.marketwide;

import com.sunmo.stockplatform.marketwide.application.*;
import com.sunmo.stockplatform.marketwide.domain.*;
import com.sunmo.stockplatform.market.config.*;
import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BroadEnrichmentQueueTest {
    private final BroadQuoteResolver quotes = mock(BroadQuoteResolver.class);
    private final BroadSnapshotService snapshots = mock(BroadSnapshotService.class);
    private final MutableClock clock = new MutableClock();
    private BroadEnrichmentQueue queue(boolean enabled, int capacity) {
        return new BroadEnrichmentQueue(quotes, snapshots,
                new BroadEnrichmentProperties(enabled, capacity, 2, Duration.ofMinutes(3), Duration.ofSeconds(10)),
                new MarketSessionPolicy(new MarketWideScheduleProperties(false, 120, 30, false,
                        null, null, null, null, null, List.of())),
                new ClosingRecommendationProperties(20, 4, new BigDecimal("55"), LocalTime.of(14, 30),
                        LocalTime.of(15, 0), LocalTime.of(15, 20), Duration.ofSeconds(10)), clock);
    }
    private BroadSnapshotService.Capture capture(String code) {
        Stock stock = mock(Stock.class);
        when(stock.getStockCode()).thenReturn(code);
        return new BroadSnapshotService.Capture(new BroadCandidate(stock, BigDecimal.TEN, Map.of(), null),
                null, BigDecimal.ZERO, "DETAIL_QUOTE_BUDGET_EXHAUSTED");
    }
    private BroadQuoteData data() {
        return new BroadQuoteData(BigDecimal.TEN, BigDecimal.ZERO, 10L, BigDecimal.valueOf(100),
                null, null, null, null, clock.instant(), "REST");
    }

    @Test
    void deduplicatesAndBoundsCapacityThenAppendsSuccessfulObservation() {
        var queue = queue(true, 1);
        var capture = capture("005930");
        queue.enqueue(List.of(capture, capture, capture("000660")));
        assertThat(queue.snapshot().accepted()).isEqualTo(1);
        assertThat(queue.snapshot().coalesced()).isEqualTo(1);
        assertThat(queue.snapshot().rejected()).isEqualTo(1);
        var data = data();
        when(quotes.resolve(any(), eq(true))).thenReturn(new BroadQuoteResolver.Resolution(data, null, true));
        queue.processOne();
        queue.processOne();
        verify(quotes, times(1)).resolve(any(), eq(true));
        verify(snapshots).saveEnriched(clock.instant(), capture.candidate(), data);
        assertThat(queue.snapshot().succeeded()).isEqualTo(1);
    }

    @Test
    void retriesOnlyAfterDelayAndStopsAtAttemptLimit() {
        var queue = queue(true, 10);
        queue.enqueue(List.of(capture("005930")));
        when(quotes.resolve(any(), eq(true))).thenReturn(new BroadQuoteResolver.Resolution(null, "KIS unavailable", true));
        queue.processOne();
        queue.processOne();
        verify(quotes, times(1)).resolve(any(), eq(true));
        clock.now = clock.now.plusSeconds(10);
        queue.processOne();
        clock.now = clock.now.plusSeconds(30);
        queue.processOne();
        verify(quotes, times(2)).resolve(any(), eq(true));
        assertThat(queue.snapshot().exhausted()).isEqualTo(1);
        verifyNoInteractions(snapshots);
    }

    @Test
    void expiresOldJobsAndDoesNotResetAgeOnRepeatedScans() {
        var queue = queue(true, 10);
        var capture = capture("005930");
        queue.enqueue(List.of(capture));
        clock.now = clock.now.plusSeconds(120);
        queue.enqueue(List.of(capture));
        clock.now = clock.now.plusSeconds(61);
        queue.processOne();
        assertThat(queue.snapshot().expired()).isEqualTo(1);
        verifyNoInteractions(quotes, snapshots);
    }

    @Test
    void crossingFreezeDuringRequestDoesNotSaveLatePrice() {
        clock.now = Instant.parse("2026-09-14T05:59:59Z");
        var queue = queue(true, 10);
        queue.enqueue(List.of(capture("005930")));
        when(quotes.resolve(any(), eq(true))).thenAnswer(call -> {
            clock.now = clock.now.plusSeconds(2);
            return new BroadQuoteResolver.Resolution(data(), null, true);
        });
        queue.processOne();
        assertThat(queue.snapshot().expired()).isEqualTo(1);
        verifyNoInteractions(snapshots);
    }

    @Test
    void disabledAndOutsideSessionDoNotCallKis() {
        var disabled = queue(false, 10);
        disabled.enqueue(List.of(capture("005930")));
        disabled.processOne();
        assertThat(disabled.snapshot().accepted()).isZero();
        clock.now = Instant.parse("2026-09-14T07:00:00Z");
        var afterClose = queue(true, 10);
        afterClose.enqueue(List.of(capture("005930")));
        afterClose.processOne();
        assertThat(afterClose.snapshot().rejected()).isEqualTo(1);
        verifyNoInteractions(quotes, snapshots);
    }

    @Test
    void databaseFailureIsRetriedWithoutEscapingWorker() {
        var queue = queue(true, 10);
        queue.enqueue(List.of(capture("005930")));
        when(quotes.resolve(any(), eq(true))).thenAnswer(call -> new BroadQuoteResolver.Resolution(data(), null, true));
        when(snapshots.saveEnriched(any(), any(), any())).thenThrow(new IllegalStateException("DB unavailable"));
        queue.processOne();
        assertThat(queue.snapshot().states()).containsEntry("RETRY", 1);
        assertThat(queue.snapshot().lastError()).isEqualTo("DB unavailable");
    }

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-14T05:30:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
