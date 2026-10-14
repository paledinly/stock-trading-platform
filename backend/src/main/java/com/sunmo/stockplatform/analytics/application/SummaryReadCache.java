/*
 * 자주 요청되는 요약 응답을 잠깐(기본 60초, 최대 5분) 메모리에 보관해 두는 작은 캐시(결과를 잠시 보관하는 곳) 파일이에요.
 * 같은 조건 요청이 다시 오면 계산을 건너뛰고 보관한 응답을 돌려줘요. 최대 64개까지만 보관해요.
 * ScannerAnalyticsService가 통계 응답을 보관할 때 써요.
 */
package com.sunmo.stockplatform.analytics.application;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Small process-local cache of immutable response DTOs, never managed JPA entities.
 *
 * 이 서버 프로세스 안에서만 쓰는 작은 보관함이에요. 바뀌지 않는 응답 객체만 넣고, DB 엔티티는 넣지 않아요.
 * 유통기한 스티커가 붙은 반찬통처럼, 기한(TTL)이 지난 것은 버리고 새로 만들어 담아요.
 */
@Component
public class SummaryReadCache {
    private final Duration ttl;
    private final Clock clock;
    private final Map<Object, Entry> entries = new LinkedHashMap<>();
    /**
     * 스프링이 쓰는 생성자예요. 설정값 analytics.summary-cache-ttl(기본 60초)을 보관 시간으로 써요.
     * 받는 것: 보관 시간. 돌려주는 것: 캐시 객체예요.
     */
    @Autowired
    public SummaryReadCache(@Value("${analytics.summary-cache-ttl:60s}") Duration ttl) {
        this(ttl, Clock.systemUTC());
    }
    /**
     * 보관 시간과 시계를 직접 받아 캐시를 만들어요(테스트에서 시계를 바꿔 끼울 수 있어요).
     * 받는 것: 보관 시간(0~5분, 0이면 보관하지 않음), 시계. 돌려주는 것: 캐시 객체예요. 범위를 벗어나면 예외를 던져요.
     */
    public SummaryReadCache(Duration ttl, Clock clock) {
        if (ttl.isNegative() || ttl.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("Summary TTL must be between zero and five minutes");
        this.ttl = ttl; this.clock = clock;
    }
    /**
     * 키에 맞는 보관 값을 돌려주고, 없으면 loader로 새로 만들어 보관한 뒤 돌려줘요.
     * 받는 것: 조건을 나타내는 키, 값을 만드는 함수(loader). 돌려주는 것: 보관 값 또는 새로 만든 값이에요.
     * 한 번에 한 요청만 들어오도록 잠겨 있어서, 새로 계산하는 동안 다른 요청은 기다려요.
     */
    @SuppressWarnings("unchecked")
    public synchronized <T> T get(Object key, Supplier<T> loader) {
        Instant now = clock.instant();
        // 기한이 지난 항목부터 정리해요.
        entries.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
        Entry cached = entries.get(key);
        if (cached != null) return (T) cached.value();
        T response = loader.get();
        if (!ttl.isZero()) {
            // 64개가 차면 가장 먼저 넣은 항목 하나를 버려요.
            if (entries.size() >= 64) entries.remove(entries.keySet().iterator().next());
            entries.put(key, new Entry(response, clock.instant().plus(ttl)));
        }
        return response;
    }
    /** 보관 항목 하나예요. 값과 버릴 시각(expiresAt)을 함께 적어 둔 라벨 붙은 통이에요. */
    private record Entry(Object value, Instant expiresAt) {}
}
