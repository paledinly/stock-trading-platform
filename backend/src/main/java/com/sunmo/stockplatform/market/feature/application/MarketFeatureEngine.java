package com.sunmo.stockplatform.market.feature.application;

import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MarketFeatureEngine {
    private final Map<String, IntradayFeatureState> states = new ConcurrentHashMap<>();
    private final Map<String, MarketFeatureSnapshot> previous = new ConcurrentHashMap<>();
    private final RealtimeDiagnostics diagnostics;

    public MarketFeatureEngine(RealtimeDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    public MarketFeatureSnapshot onTick(MarketTick tick) {
        IntradayFeatureState state = states.computeIfAbsent(tick.stockCode(), IntradayFeatureState::new);
        MarketFeatureSnapshot before = state.latest();
        MarketFeatureSnapshot snapshot = state.accept(tick);
        if (before != null && snapshot != null && !bucket(before.occurredAt()).equals(bucket(snapshot.occurredAt())))
            previous.put(tick.stockCode(), before);
        diagnostics.featureSnapshot(states.size());
        return snapshot;
    }

    public Optional<MarketFeatureSnapshot> latest(String stockCode) {
        IntradayFeatureState state = states.get(stockCode);
        return state == null ? Optional.empty() : Optional.ofNullable(state.latest());
    }

    public Optional<MarketFeatureSnapshot> at(String stockCode, Instant candleStart) {
        MarketFeatureSnapshot latest = latest(stockCode).orElse(null);
        if (inBucket(latest, candleStart)) return Optional.of(latest);
        MarketFeatureSnapshot prior = previous.get(stockCode);
        return inBucket(prior, candleStart) ? Optional.of(prior) : Optional.empty();
    }

    private boolean inBucket(MarketFeatureSnapshot value, Instant start) {
        return value != null && !value.occurredAt().isBefore(start)
                && value.occurredAt().isBefore(start.plus(Duration.ofMinutes(5)));
    }

    private Instant bucket(Instant value) {
        java.time.ZonedDateTime time = value.atZone(java.time.ZoneId.of("Asia/Seoul"));
        return time.withMinute(time.getMinute() - time.getMinute() % 5).withSecond(0).withNano(0).toInstant();
    }

    public int trackedStocks() {
        return states.size();
    }
}
