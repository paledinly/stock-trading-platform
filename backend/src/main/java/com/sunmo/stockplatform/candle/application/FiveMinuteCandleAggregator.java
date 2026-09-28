package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.market.domain.MarketTick;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FiveMinuteCandleAggregator {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final Duration watermark;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, Instant> finalizedThrough = new ConcurrentHashMap<>();

    public FiveMinuteCandleAggregator(Duration watermark) {
        this.watermark = watermark;
    }

    public synchronized List<CandleSnapshot> accept(MarketTick tick) {
        Instant bucket = bucketStart(tick.occurredAt());
        State state = states.get(tick.stockCode());
        List<CandleSnapshot> result = new ArrayList<>();
        if (state == null) {
            Instant finalized = finalizedThrough.get(tick.stockCode());
            if (finalized != null && !bucket.isAfter(finalized))
                return result;
            states.put(tick.stockCode(), State.first(tick, bucket));
            result.add(states.get(tick.stockCode()).snapshot(false));
            return result;
        }
        if (bucket.isAfter(state.start)) {
            finalizedThrough.put(tick.stockCode(), state.start);
            result.add(state.snapshot(true));
            state = State.first(tick, bucket);
            states.put(tick.stockCode(), state);
            result.add(state.snapshot(false));
            return result;
        }
        if (bucket.isBefore(state.start))
            return result;
        if (bucket.equals(state.start)) {
            if (state.apply(tick))
                result.add(state.snapshot(false));
        }
        return result;
    }

    public synchronized List<CandleSnapshot> flush(Instant now) {
        List<CandleSnapshot> closed = new ArrayList<>();
        Iterator<Map.Entry<String, State>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            State state = iterator.next().getValue();
            if (!now.isBefore(state.start.plus(Duration.ofMinutes(5)).plus(watermark))) {
                finalizedThrough.put(state.code, state.start);
                closed.add(state.snapshot(true));
                iterator.remove();
            }
        }
        return closed;
    }

    static Instant bucketStart(Instant instant) {
        ZonedDateTime time = instant.atZone(SEOUL);
        ZonedDateTime open = time.toLocalDate().atTime(9, 0).atZone(SEOUL);
        ZonedDateTime close = open.plusMinutes(390);
        if (time.isBefore(open) || time.isAfter(close))
            throw new IllegalArgumentException("Tick outside regular KRX session");
        if (time.equals(close))
            return close.minusMinutes(5).toInstant();
        long minutes = ChronoUnit.MINUTES.between(open, time);
        return open.plusMinutes((minutes / 5) * 5).toInstant();
    }

    private static final class State {
        String code;
        Instant start;
        BigDecimal open, high, low, close, value;
        long volume, lastCum;
        BigDecimal lastCumValue;
        Instant lastEvent;
        long lastSequence;
        int revision;

        static State first(MarketTick tick, Instant start) {
            State s = new State();
            s.code = tick.stockCode();
            s.start = start;
            s.open = s.high = s.low = s.close = tick.price();
            s.volume = tick.tradeVolume();
            s.value = tick.price().multiply(BigDecimal.valueOf(tick.tradeVolume()));
            s.lastCum = tick.cumulativeVolume();
            s.lastCumValue = tick.cumulativeTradingValue();
            s.lastEvent = tick.occurredAt();
            s.lastSequence = tick.sequence();
            return s;
        }

        boolean apply(MarketTick tick) {
            if (tick.sequence() > 0 && tick.sequence() <= lastSequence)
                return false;
            if (tick.occurredAt().isBefore(lastEvent))
                return false;
            if (tick.occurredAt().equals(lastEvent) && tick.cumulativeVolume() <= lastCum
                    && (tick.cumulativeTradingValue() == null || lastCumValue == null
                            || tick.cumulativeTradingValue().compareTo(lastCumValue) <= 0))
                return false;
            high = high.max(tick.price());
            low = low.min(tick.price());
            if (!tick.occurredAt().isBefore(lastEvent)) {
                close = tick.price();
                lastEvent = tick.occurredAt();
            }
            long delta = tick.cumulativeVolume() - lastCum;
            volume += delta >= 0 ? delta : tick.tradeVolume();
            BigDecimal cv = tick.cumulativeTradingValue();
            if (cv != null && lastCumValue != null && cv.compareTo(lastCumValue) >= 0)
                value = value.add(cv.subtract(lastCumValue));
            else
                value = value.add(tick.price().multiply(BigDecimal.valueOf(tick.tradeVolume())));
            lastCum = tick.cumulativeVolume();
            lastCumValue = cv;
            lastSequence = Math.max(lastSequence, tick.sequence());
            return true;
        }

        CandleSnapshot snapshot(boolean done) {
            return new CandleSnapshot(code, start, open, high, low, close, volume, value, done, revision);
        }
    }
}
