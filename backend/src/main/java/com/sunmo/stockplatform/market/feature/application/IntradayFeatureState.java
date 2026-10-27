/*
 * 종목 하나의 당일 실시간 Feature(가격·거래량에서 뽑아낸 판단용 지표)를 틱마다 갱신하는 상태 계산기예요.
 * VWAP, VWAP 거리·기울기, 거래량·거래대금 배수, 매수·매도 체결 증분, 당일 고가 대비 거리 등을 계산해 스냅샷으로 남겨요.
 * MarketFeatureEngine이 종목마다 하나씩 만들어 틱을 넘겨요(같은 패키지 안에서만 써요).
 */
package com.sunmo.stockplatform.market.feature.application;

import com.sunmo.stockplatform.closing.application.MarketSessionHours;

import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 종목 하나의 당일 지표 수첩이에요.
 * 가계부처럼 틱이 올 때마다 오늘 누적 금액·수량을 맞춰 적고, 5분 단위 칸을 넘길 때마다 직전 칸 합계를 보관해 "최근 6칸 평균 대비 지금 칸"을 계산해요.
 * 날짜가 바뀌면 수첩을 새로 시작해요. 한 스레드(틱 처리 작업자)에서만 쓰는 전제라 잠금이 없어요.
 */
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

    /** 하는 일: 종목코드만 정해 빈 수첩을 만들어요. 받는 것: 종목코드. */
    IntradayFeatureState(String stockCode) {
        this.stockCode = stockCode;
    }

    /**
     * 하는 일: 틱 하나를 반영해 Feature 스냅샷을 새로 계산해요.
     * 받는 것: 틱.
     * 돌려주는 것: 새 스냅샷. 중복·과거 틱이면 계산하지 않고 이전 스냅샷(latest)을 그대로 돌려줘요.
     */
    MarketFeatureSnapshot accept(MarketTick tick) {
        // 1) 이미 본 순번, 더 과거 시각, 같은 시각인데 누적 거래량이 늘지 않은 틱은 무시해요(늦은 틱이 값을 덮지 않게).
        if (latest != null && tick.sequence() > 0 && tick.sequence() <= lastSequence)
            return latest;
        if (latest != null && tick.occurredAt().isBefore(latest.occurredAt()))
            return latest;
        if (latest != null && tick.occurredAt().equals(latest.occurredAt())
                && tick.cumulativeVolume() <= latest.cumulativeVolume())
            return latest;
        // 2) 날짜가 바뀌면 초기화하고, 5분 칸이 넘어가면 직전 칸 합계를 baseline에 넣어요.
        //    칸이 바로 다음 칸이 아니라 건너뛰었으면(공백) 연속성이 깨진 것으로 보고 baseline을 비워요.
        if (businessDate == null || !businessDate.equals(tick.businessDate()))
            reset(tick.businessDate());
        Instant nextBucket = bucketStart(tick.occurredAt());
        if (bucketStart != null && nextBucket.isBefore(bucketStart))
            return latest;
        if (bucketStart == null)
            bucketStart = nextBucket;
        if (nextBucket.isAfter(bucketStart)) {
            if (nextBucket.equals(bucketStart.plus(Duration.ofMinutes(5)))) {
                remember(completedVolumes, bucketVolume);
                remember(completedValues, bucketValue);
            } else {
                completedVolumes.clear();
                completedValues.clear();
            }
            bucketStart = nextBucket;
            bucketVolume = 0;
            bucketValue = BigDecimal.ZERO;
        }

        // 3) 누적값의 차이로 이번 틱의 거래량·거래대금·매수/매도 증분을 구해 현재 칸에 더해요.
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

        // 4) 당일 고가, VWAP(거래량 가중 평균가격: 오늘 거래된 금액 ÷ 거래량), 직전 틱 대비 VWAP 변화율을 계산해요.
        BigDecimal high = positiveOr(tick.highPrice(), dayHigh == null ? tick.price() : dayHigh).max(tick.price());
        dayHigh = dayHigh == null ? high : dayHigh.max(high);
        BigDecimal vwap = vwap(tick, valueDelta, volumeDelta);
        BigDecimal slope = lastVwap == null || lastVwap.signum() == 0 || lastVwapAt == null
                || !tick.occurredAt().isAfter(lastVwapAt)
                        ? null
                        : rate(vwap.subtract(lastVwap), lastVwap);
        lastVwap = vwap;
        lastVwapAt = tick.occurredAt();

        // 5) 결과: 모든 값을 스냅샷 하나로 묶어 최신값으로 기억해요.
        latest = new MarketFeatureSnapshot(stockCode, tick.businessDate(), tick.occurredAt(), tick.price(),
                tick.cumulativeVolume(), tick.cumulativeTradingValue(), tick.openPrice(), tick.highPrice(),
                tick.lowPrice(), vwap, rate(tick.price().subtract(vwap), vwap), slope,
                ratio(bucketVolume, completedVolumes),
                ratio(bucketValue, completedValues), tick.tradeStrength(), buyDelta, sellDelta, tick.buyRatio(),
                rate(dayHigh.subtract(tick.price()), dayHigh), tick.tradingHalted(), tick.viStandardPrice(),
                MarketFeatureSnapshot.VERSION);
        return latest;
    }

    /** 하는 일: 마지막으로 계산한 스냅샷을 돌려줘요. 아직 없으면 null이에요. */
    MarketFeatureSnapshot latest() {
        return latest;
    }

    /** 하는 일: 새 거래일로 바뀌었을 때 모든 누적값과 baseline을 비우고 날짜만 새로 정해요. 받는 것: 새 거래일. */
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

    /**
     * 하는 일: 시각이 속한 5분 칸의 시작시각을 09:00 기준으로 계산해요. 09:00 이전은 09:00 칸으로 봐요.
     * 받는 것: 시각. 돌려주는 것: 칸 시작시각.
     * 5분봉 집계기(FiveMinuteCandleAggregator.bucketStart)와 같은 규칙으로, 15:30 이후의 종가 단일가 체결은
     * 마지막 15:25 칸에 합쳐요. 그래서 종가 체결 틱의 거래량·거래대금 배수도 15:25 봉과 같은 칸 기준으로 계산돼요.
     */
    private Instant bucketStart(Instant instant) {
        ZonedDateTime time = instant.atZone(SEOUL);
        var date = time.toLocalDate();
        ZonedDateTime open = date.atTime(MarketSessionHours.open(date)).atZone(SEOUL);
        ZonedDateTime close = date.atTime(MarketSessionHours.close(date)).atZone(SEOUL);
        if (!time.isBefore(close))
            return close.minusMinutes(5).toInstant();
        long minutes = Math.max(0, ChronoUnit.MINUTES.between(open, time));
        return open.plusMinutes((minutes / 5) * 5).toInstant();
    }

    /**
     * 하는 일: 이번 틱의 거래량 증분을 구해요. 누적 거래량 차이를 쓰고, 첫 틱이거나 누적값이 줄었으면 틱 체결량으로 대신해요.
     * 받는 것: 틱. 돌려주는 것: 거래량 증분.
     */
    private long volumeDelta(MarketTick tick) {
        if (lastCumulativeVolume == null)
            return tick.tradeVolume();
        long delta = tick.cumulativeVolume() - lastCumulativeVolume;
        return delta >= 0 ? delta : tick.tradeVolume();
    }

    /**
     * 하는 일: 이번 틱의 거래대금 증분을 구해요. 누적 거래대금 차이를 우선 쓰고, 없으면 가격 × 거래량 증분으로 추정해요.
     * 받는 것: 틱, 거래량 증분. 돌려주는 것: 거래대금 증분.
     */
    private BigDecimal valueDelta(MarketTick tick, long volumeDelta) {
        BigDecimal cumulative = tick.cumulativeTradingValue();
        if (lastCumulativeValue != null && cumulative != null && cumulative.compareTo(lastCumulativeValue) >= 0) {
            return cumulative.subtract(lastCumulativeValue);
        }
        return tick.price().multiply(BigDecimal.valueOf(volumeDelta));
    }

    /**
     * 하는 일: 누적 매수(또는 매도) 체결량의 직전 틱 대비 증분을 구해요. 값이 없거나 줄었으면 0이에요.
     * 받는 것: 현재 누적값, 직전 누적값. 돌려주는 것: 0 이상 증분.
     */
    private long sideDelta(Long current, Long previous) {
        if (current == null || previous == null)
            return 0;
        long delta = current - previous;
        return Math.max(delta, 0);
    }

    /**
     * 하는 일: VWAP을 계산해요. 당일 누적 거래대금 ÷ 누적 거래량을 우선 쓰고,
     * 누적 거래대금이 없으면 이번 틱의 증분으로 만든 대체값(또는 현재가)을 써요. 대체값은 당일 VWAP과 품질이 달라요.
     * 받는 것: 틱, 거래대금 증분, 거래량 증분. 돌려주는 것: VWAP(소수 6자리).
     */
    private BigDecimal vwap(MarketTick tick, BigDecimal valueDelta, long volumeDelta) {
        if (tick.cumulativeTradingValue() != null && tick.cumulativeVolume() > 0) {
            return tick.cumulativeTradingValue().divide(BigDecimal.valueOf(tick.cumulativeVolume()), 6,
                    RoundingMode.HALF_UP);
        }
        return volumeDelta == 0 ? tick.price()
                : valueDelta.divide(BigDecimal.valueOf(volumeDelta), 6, RoundingMode.HALF_UP);
    }

    /**
     * 하는 일: 현재 칸 거래량 ÷ 직전 6칸 평균 거래량(거래량 배수)을 구해요.
     * 받는 것: 현재 칸 거래량, 직전 칸들. 돌려주는 것: 배수. 직전 칸이 6개 미만이거나 합이 0이면 null.
     */
    private BigDecimal ratio(long current, Deque<Long> baseline) {
        if (baseline.size() < BASELINE_BUCKETS)
            return null;
        long sum = baseline.stream().mapToLong(Long::longValue).sum();
        return sum == 0 ? null
                : BigDecimal.valueOf(current)
                        .divide(BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(baseline.size()), 6,
                                RoundingMode.HALF_UP), 6, RoundingMode.HALF_UP);
    }

    /**
     * 하는 일: 현재 칸 거래대금 ÷ 직전 6칸 평균 거래대금(거래대금 배수)을 구해요.
     * 받는 것: 현재 칸 거래대금, 직전 칸들. 돌려주는 것: 배수. 직전 칸이 6개 미만이거나 합이 0이면 null.
     */
    private BigDecimal ratio(BigDecimal current, Deque<BigDecimal> baseline) {
        if (baseline.size() < BASELINE_BUCKETS)
            return null;
        BigDecimal sum = baseline.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.signum() == 0)
            return null;
        BigDecimal average = sum.divide(BigDecimal.valueOf(baseline.size()), 6, RoundingMode.HALF_UP);
        return current.divide(average, 6, RoundingMode.HALF_UP);
    }

    /**
     * 하는 일: value ÷ base × 100(퍼센트)을 계산해요.
     * 받는 것: 분자, 분모. 돌려주는 것: 퍼센트(소수 6자리). 값이 없거나 분모가 0이면 null.
     */
    private BigDecimal rate(BigDecimal value, BigDecimal base) {
        if (value == null || base == null || base.signum() == 0)
            return null;
        return value.divide(base, 8, RoundingMode.HALF_UP).multiply(HUNDRED).setScale(6, RoundingMode.HALF_UP);
    }

    /** 하는 일: 값이 양수면 그대로, 아니면(null·0·음수) 대체값을 돌려줘요. */
    private BigDecimal positiveOr(BigDecimal value, BigDecimal fallback) {
        return value != null && value.signum() > 0 ? value : fallback;
    }

    /** 하는 일: 값을 뒤에 넣고, 최근 6개만 남도록 오래된 것을 앞에서 버려요. */
    private <T> void remember(Deque<T> deque, T value) {
        deque.addLast(value);
        while (deque.size() > BASELINE_BUCKETS)
            deque.removeFirst();
    }
}
