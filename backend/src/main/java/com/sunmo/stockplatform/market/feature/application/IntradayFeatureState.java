package com.sunmo.stockplatform.market.feature.application;

import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;

final class IntradayFeatureState {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int BASELINE_BUCKETS = 6;
    private final String stockCode;
    private LocalDate businessDate;
    private Instant bucketStart;
    private long bucketVolume;
    private BigDecimal bucketValue = BigDecimal.ZERO;
    private final Deque<Long> completedVolumes = new ArrayDeque<>();
    private final Deque<BigDecimal> completedValues = new ArrayDeque<>();
    private Long lastCumulativeVolume;
    private BigDecimal lastCumulativeValue;
    private Long lastCumulativeBuyVolume;
    private Long lastCumulativeSellVolume;
    private long lastSequence;
    private BigDecimal lastVwap;
    private Instant lastVwapAt;
    private BigDecimal dayHigh;
    private MarketFeatureSnapshot latest;

    IntradayFeatureState(String stockCode) {
        this.stockCode = stockCode;
    }

    MarketFeatureSnapshot accept(MarketTick tick) {
        if (latest != null && tick.sequence() > 0 && tick.sequence() <= lastSequence)
            return latest;
        if (businessDate == null || !businessDate.equals(tick.businessDate()))
            reset(tick.businessDate());
        Instant nextBucket = bucketStart(tick.occurredAt());
        if (bucketStart != null && nextBucket.isBefore(bucketStart))
            return latest;
        if (bucketStart == null)
            bucketStart = nextBucket;
        if (nextBucket.isAfter(bucketStart)) {
            remember(completedVolumes, bucketVolume);
            remember(completedValues, bucketValue);
            bucketStart = nextBucket;
            bucketVolume = 0;
            bucketValue = BigDecimal.ZERO;
        }

        long volumeDelta = volumeDelta(tick);
        BigDecimal valueDelta = valueDelta(tick, volumeDelta);
        long buyDelta = sideDelta(tick.cumulativeBuyVolume(), lastCumulativeBuyVolume);
        long sellDelta = sideDelta(tick.cumulativeSellVolume(), lastCumulativeSellVolume);
        bucketVolume += volumeDelta;
        bucketValue = bucketValue.add(valueDelta);
        lastCumulativeVolume = tick.cumulativeVolume();
        lastCumulativeValue = tick.cumulativeTradingValue();
        lastCumulativeBuyVolume = tick.cumulativeBuyVolume();
        lastCumulativeSellVolume = tick.cumulativeSellVolume();
        lastSequence = Math.max(lastSequence, tick.sequence());

        BigDecimal high = positiveOr(tick.highPrice(), dayHigh == null ? tick.price() : dayHigh).max(tick.price());
        dayHigh = dayHigh == null ? high : dayHigh.max(high);
        BigDecimal vwap = vwap(tick, valueDelta, volumeDelta);
        BigDecimal slope = lastVwap == null || lastVwap.signum() == 0 || lastVwapAt == null
                || !tick.occurredAt().isAfter(lastVwapAt)
                        ? null
                        : rate(vwap.subtract(lastVwap), lastVwap);
        lastVwap = vwap;
        lastVwapAt = tick.occurredAt();

        latest = new MarketFeatureSnapshot(stockCode, tick.businessDate(), tick.occurredAt(), tick.price(),
                tick.cumulativeVolume(), tick.cumulativeTradingValue(), tick.openPrice(), tick.highPrice(),
                tick.lowPrice(), vwap, rate(tick.price().subtract(vwap), vwap), slope,
                ratio(bucketVolume, completedVolumes),
                ratio(bucketValue, completedValues), tick.tradeStrength(), buyDelta, sellDelta, tick.buyRatio(),
                rate(dayHigh.subtract(tick.price()), dayHigh), tick.tradingHalted(), tick.viStandardPrice(),
                MarketFeatureSnapshot.VERSION);
        return latest;
    }

    MarketFeatureSnapshot latest() {
        return latest;
    }

    private void reset(LocalDate date) {
        businessDate = date;
        bucketStart = null;
        bucketVolume = 0;
        bucketValue = BigDecimal.ZERO;
        completedVolumes.clear();
        completedValues.clear();
        lastCumulativeVolume = null;
        lastCumulativeValue = null;
        lastCumulativeBuyVolume = null;
        lastCumulativeSellVolume = null;
        lastSequence = 0;
        lastVwap = null;
        lastVwapAt = null;
        dayHigh = null;
        latest = null;
    }

    private Instant bucketStart(Instant instant) {
        ZonedDateTime time = instant.atZone(SEOUL);
        ZonedDateTime open = time.toLocalDate().atTime(9, 0).atZone(SEOUL);
        long minutes = Math.max(0, ChronoUnit.MINUTES.between(open, time));
        return open.plusMinutes((minutes / 5) * 5).toInstant();
    }

    private long volumeDelta(MarketTick tick) {
        if (lastCumulativeVolume == null)
            return tick.tradeVolume();
        long delta = tick.cumulativeVolume() - lastCumulativeVolume;
        return delta >= 0 ? delta : tick.tradeVolume();
    }

    private BigDecimal valueDelta(MarketTick tick, long volumeDelta) {
        BigDecimal cumulative = tick.cumulativeTradingValue();
        if (lastCumulativeValue != null && cumulative != null && cumulative.compareTo(lastCumulativeValue) >= 0) {
            return cumulative.subtract(lastCumulativeValue);
        }
        return tick.price().multiply(BigDecimal.valueOf(volumeDelta));
    }

    private long sideDelta(Long current, Long previous) {
        if (current == null || previous == null)
            return 0;
        long delta = current - previous;
        return Math.max(delta, 0);
    }

    private BigDecimal vwap(MarketTick tick, BigDecimal valueDelta, long volumeDelta) {
        if (tick.cumulativeTradingValue() != null && tick.cumulativeVolume() > 0) {
            return tick.cumulativeTradingValue().divide(BigDecimal.valueOf(tick.cumulativeVolume()), 6,
                    RoundingMode.HALF_UP);
        }
        return volumeDelta == 0 ? tick.price()
                : valueDelta.divide(BigDecimal.valueOf(volumeDelta), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(long current, Deque<Long> baseline) {
        if (baseline.isEmpty())
            return null;
        long sum = baseline.stream().mapToLong(Long::longValue).sum();
        return sum == 0 ? null
                : BigDecimal.valueOf(current)
                        .divide(BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(baseline.size()), 6,
                                RoundingMode.HALF_UP), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(BigDecimal current, Deque<BigDecimal> baseline) {
        if (baseline.isEmpty())
            return null;
        BigDecimal sum = baseline.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.signum() == 0)
            return null;
        BigDecimal average = sum.divide(BigDecimal.valueOf(baseline.size()), 6, RoundingMode.HALF_UP);
        return current.divide(average, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(BigDecimal value, BigDecimal base) {
        if (value == null || base == null || base.signum() == 0)
            return null;
        return value.divide(base, 8, RoundingMode.HALF_UP).multiply(HUNDRED).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal positiveOr(BigDecimal value, BigDecimal fallback) {
        return value != null && value.signum() > 0 ? value : fallback;
    }

    private <T> void remember(Deque<T> deque, T value) {
        deque.addLast(value);
        while (deque.size() > BASELINE_BUCKETS)
            deque.removeFirst();
    }
}
