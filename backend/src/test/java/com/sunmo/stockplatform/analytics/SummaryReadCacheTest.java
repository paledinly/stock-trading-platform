package com.sunmo.stockplatform.analytics;
import com.sunmo.stockplatform.analytics.application.SummaryReadCache;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class SummaryReadCacheTest {
    @Test
    void reusesSameKeySeparatesFiltersAndExpires() {
        var clock = new MutableClock();
        var cache = new SummaryReadCache(Duration.ofSeconds(60), clock);
        var calls = new AtomicInteger();
        assertThat(cache.get("one", calls::incrementAndGet)).isEqualTo(1);
        assertThat(cache.get("one", calls::incrementAndGet)).isEqualTo(1);
        assertThat(cache.get("two", calls::incrementAndGet)).isEqualTo(2);
        clock.now = clock.now.plusSeconds(60);
        assertThat(cache.get("one", calls::incrementAndGet)).isEqualTo(3);
    }
    @Test
    void errorsAreNotCachedAndZeroTtlDisablesCache() {
        var cache = new SummaryReadCache(Duration.ZERO);
        assertThatThrownBy(() -> cache.get("one", () -> { throw new IllegalStateException(); }))
                .isInstanceOf(IllegalStateException.class);
        var calls = new AtomicInteger();
        assertThat(cache.get("one", calls::incrementAndGet)).isEqualTo(1);
        assertThat(cache.get("one", calls::incrementAndGet)).isEqualTo(2);
    }
    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-14T05:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
