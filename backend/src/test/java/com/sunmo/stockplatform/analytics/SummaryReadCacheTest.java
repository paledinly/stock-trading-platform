/*
 * SummaryReadCache(요약 조회 결과를 잠깐 저장해 두는 캐시)를 확인해요.
 * 무거운 집계 조회를 반복하지 않으면서도 만료 시간이 지나면 새로 읽어야 해서 중요해요.
 */
package com.sunmo.stockplatform.analytics;
import com.sunmo.stockplatform.analytics.application.SummaryReadCache;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** 요약 조회 캐시의 재사용·만료 규칙을 점검하는 테스트예요. */
class SummaryReadCacheTest {
    /**
     * 같은 키는 재사용하고, 다른 키는 따로 저장하고, TTL(유효 시간)이 지나면 다시 계산하는지 확인해요.
     * 준비: 손으로 시간을 옮기는 시계와 TTL 60초 캐시를 만들어요.
     * 기대: "one" 두 번은 1, "two"는 2, 60초 뒤 "one"은 3이에요.
     */
    @Test
    void reusesSameKeySeparatesFiltersAndExpires() {
        var clock = new MutableClock();
        var cache = new SummaryReadCache(Duration.ofSeconds(60), clock);
        var calls = new AtomicInteger();
        assertThat(cache.get("one", calls::incrementAndGet)).isEqualTo(1);
        assertThat(cache.get("one", calls::incrementAndGet)).isEqualTo(1);
        assertThat(cache.get("two", calls::incrementAndGet)).isEqualTo(2);
        // 시간을 60초 앞으로 옮겨 만료시켜요.
        clock.now = clock.now.plusSeconds(60);
        assertThat(cache.get("one", calls::incrementAndGet)).isEqualTo(3);
    }
    /**
     * 예외 결과는 캐시하지 않고, TTL 0이면 캐시를 끄는지 확인해요.
     * 준비: TTL 0 캐시에서 먼저 예외를 던지는 계산을 호출해요.
     * 기대: 예외가 그대로 나가고, 이후 같은 키도 매번 새로 계산돼요(1, 2).
     */
    @Test
    void errorsAreNotCachedAndZeroTtlDisablesCache() {
        var cache = new SummaryReadCache(Duration.ZERO);
        assertThatThrownBy(() -> cache.get("one", () -> { throw new IllegalStateException(); }))
                .isInstanceOf(IllegalStateException.class);
        var calls = new AtomicInteger();
        assertThat(cache.get("one", calls::incrementAndGet)).isEqualTo(1);
        assertThat(cache.get("one", calls::incrementAndGet)).isEqualTo(2);
    }
    /** 테스트에서 현재 시각을 직접 바꿀 수 있는 시계(고정 시계 Clock를 대신하는 가변 시계)예요. */
    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-14T05:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
