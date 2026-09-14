package com.sunmo.stockplatform.analytics.application;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Small process-local cache of immutable response DTOs, never managed JPA entities. */
@Component
public class SummaryReadCache {
    private final Duration ttl;
    private final Clock clock;
    private final Map<Object, Entry> entries = new LinkedHashMap<>();
    @Autowired
    public SummaryReadCache(@Value("${analytics.summary-cache-ttl:60s}") Duration ttl) {
        this(ttl, Clock.systemUTC());
    }
    public SummaryReadCache(Duration ttl, Clock clock) {
        if (ttl.isNegative() || ttl.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("Summary TTL must be between zero and five minutes");
        this.ttl = ttl; this.clock = clock;
    }
    @SuppressWarnings("unchecked")
    public synchronized <T> T get(Object key, Supplier<T> loader) {
        Instant now = clock.instant();
        entries.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
        Entry cached = entries.get(key);
        if (cached != null) return (T) cached.value();
        T response = loader.get();
        if (!ttl.isZero()) {
            if (entries.size() >= 64) entries.remove(entries.keySet().iterator().next());
            entries.put(key, new Entry(response, clock.instant().plus(ttl)));
        }
        return response;
    }
    private record Entry(Object value, Instant expiresAt) {}
}
