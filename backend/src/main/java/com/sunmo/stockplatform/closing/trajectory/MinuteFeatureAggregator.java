package com.sunmo.stockplatform.closing.trajectory;

import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import com.sunmo.stockplatform.market.domain.MarketTick;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.*;

/** Bounded minute aggregates, never a tick journal. Finalized buckets cannot be revised. */
public class MinuteFeatureAggregator {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private final Duration watermark;
    private final Map<String, State> states = new HashMap<>();
    private LocalDate date;
    private long rejected;
    public MinuteFeatureAggregator(Duration watermark) { this.watermark = watermark; }

    public synchronized void accept(ObservedMarketTick event) {
        MarketTick tick = event.tick();
        if (!tick.businessDate().equals(event.receivedAt().atZone(ZONE).toLocalDate())
                || tick.occurredAt().isAfter(event.receivedAt())) { rejected++; return; }
        if (date != null && tick.businessDate().isBefore(date)) { rejected++; return; }
        if (!tick.businessDate().equals(date)) { states.clear(); date = tick.businessDate(); }
        LocalTime time = tick.occurredAt().atZone(ZONE).toLocalTime();
        if (time.isBefore(LocalTime.of(9, 0)) || !time.isBefore(LocalTime.of(15, 20))) return;
        Instant start = tick.occurredAt().truncatedTo(ChronoUnit.MINUTES);
        if (!event.receivedAt().isBefore(start.plusSeconds(60).plus(watermark))) { rejected++; return; }
        State state = states.computeIfAbsent(tick.stockCode(), ignored -> new State());
        MarketTick previous = state.last;
        if ((state.closedThrough != null && !start.isAfter(state.closedThrough))
                || (previous != null && (tick.occurredAt().isBefore(previous.occurredAt())
                    || tick.cumulativeVolume() <= previous.cumulativeVolume()))) { rejected++; return; }
        Bucket bucket = state.buckets.computeIfAbsent(start, ignored -> new Bucket(tick, event.receivedAt()));
        boolean continuous = previous != null
                && Duration.between(previous.occurredAt(), tick.occurredAt()).compareTo(Duration.ofMinutes(1)) <= 0
                && tick.cumulativeVolume() - previous.cumulativeVolume() == tick.tradeVolume();
        bucket.complete &= continuous;
        bucket.volume = bucket.volume.add(BigDecimal.valueOf(tick.tradeVolume()));
        BigDecimal value = delta(tick.cumulativeTradingValue(), previous == null ? null : previous.cumulativeTradingValue());
        bucket.turnover = add(bucket.turnover, value);
        bucket.buy = add(bucket.buy, delta(number(tick.cumulativeBuyVolume()), previous == null ? null : number(previous.cumulativeBuyVolume())));
        bucket.sell = add(bucket.sell, delta(number(tick.cumulativeSellVolume()), previous == null ? null : number(previous.cumulativeSellVolume())));
        bucket.high = bucket.high.max(tick.price()); bucket.low = bucket.low.min(tick.price());
        bucket.last = tick; bucket.received = event.receivedAt(); state.last = tick;
        // ponytail: at most three unpersisted minutes per symbol; failures expose gaps instead of retaining unbounded data.
        while (state.buckets.size() > 3) { state.buckets.pollFirstEntry(); rejected++; }
    }

    /** Caller acknowledges only after DB commit, so transient DB failures can retry the same aggregates. */
    public synchronized List<Minute> ready(Instant now) {
        List<Minute> result = new ArrayList<>();
        for (State state : states.values()) for (var entry : state.buckets.entrySet()) {
            if (entry.getKey().plusSeconds(60).plus(watermark).isAfter(now)) continue;
            var b = entry.getValue(); var t = b.last;
            result.add(new Minute(t.stockCode(), entry.getKey(), t.occurredAt(), b.received, now,
                    phase(entry.getKey()), b.open, b.high, b.low, t.price(), b.volume, b.turnover,
                    BigDecimal.valueOf(t.cumulativeVolume()), t.cumulativeTradingValue(), t.openPrice(),
                    t.highPrice(), t.lowPrice(), t.tradeStrength(), b.buy, b.sell, b.complete && b.turnover != null));
            state.closedThrough = entry.getKey();
        }
        return result;
    }
    public synchronized void acknowledge(List<Minute> rows) {
        for (Minute row : rows) {
            State state = states.get(row.symbol());
            if (state != null) state.buckets.remove(row.start());
        }
    }
    public synchronized long rejected() { return rejected; }
    static String phase(Instant start) {
        LocalTime t = start.atZone(ZONE).toLocalTime();
        return t.isBefore(LocalTime.of(14, 30)) ? "REGULAR" : t.isBefore(LocalTime.of(15, 0)) ? "CLOSING_PHASE_1"
                : t.isBefore(LocalTime.of(15, 10)) ? "CLOSING_PHASE_2" : "CLOSING_PHASE_3";
    }
    private static BigDecimal number(Long n) { return n == null ? null : BigDecimal.valueOf(n); }
    private static BigDecimal delta(BigDecimal a, BigDecimal b) { return a == null || b == null || a.compareTo(b) < 0 ? null : a.subtract(b); }
    private static BigDecimal add(BigDecimal a, BigDecimal b) { return a == null || b == null ? null : a.add(b); }
    private static class State {
        MarketTick last; Instant closedThrough; TreeMap<Instant, Bucket> buckets = new TreeMap<>();
    }
    private static class Bucket {
        BigDecimal open, high, low, volume = BigDecimal.ZERO, turnover = BigDecimal.ZERO,
                buy = BigDecimal.ZERO, sell = BigDecimal.ZERO;
        MarketTick last; Instant received; boolean complete = true;
        Bucket(MarketTick t, Instant receipt) { open = high = low = t.price(); last = t; received = receipt; }
    }
}
