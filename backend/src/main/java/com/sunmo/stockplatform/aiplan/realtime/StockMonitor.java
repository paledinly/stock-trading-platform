/*
 * AI 감시 종목 하나의 장중 지표를 실시간 체결(틱)로 계산하는 파일이에요.
 * 1분·5분 칸, 시가 범위(최초 5분·15분 고가·저가), VWAP, ATR5m, RVOL_5M, 자료 끊김을 메모리에서만 들고 있어요(틱은 저장하지 않아요).
 * 칸 거래량은 누적 거래량 증가분으로 더해요. KIS가 작은 체결 몇 건을 빼고 보내도 거래량은 맞아요(2026-11-11 확인).
 * 칸은 끝난 뒤 2초가 지나면 닫고, 늦게 온 틱은 닫힌 칸에 넣지 않아요. AiWatchEngine이 종목마다 하나씩 만들어 써요.
 */
package com.sunmo.stockplatform.aiplan.realtime;

import com.sunmo.stockplatform.closing.application.MarketSessionHours;
import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import com.sunmo.stockplatform.market.domain.MarketTick;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/** 종목 하나의 장중 지표 계산기예요. 스레드 안전하지 않아서 AiWatchEngine의 잠금 안에서만 불러요. */
final class StockMonitor {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 감시 종목 정보예요. conditions는 AI JSON의 entryStrategy.conditions(규칙 A)이고, 없으면 빈 목록이에요.
     */
    record Pick(long pickId, String stockCode, String stockName, Integer priority, boolean topPriority,
            java.util.List<AiCondition> conditions, boolean conditionsSupported) { }

    /**
     * 장 시작 전에 미리 계산한 과거 자료예요.
     * slotVolume: 5분 칸 시작 시각(KST) → 과거 평균 거래량, days: 그 평균에 쓴 거래일 수,
     * previousBarTurnover: 전일 5분봉 평균 거래대금, previousHigh·previousClose: 전일 일봉.
     */
    record Profile(Map<LocalTime, BigDecimal> slotVolume, int days, BigDecimal previousBarTurnover,
            BigDecimal previousHigh, BigDecimal previousClose) {
        static Profile empty() { return new Profile(Map.of(), 0, null, null, null); }
    }

    /** 확정된(또는 채우는 중인) 칸 하나예요. 거래량·거래대금은 누적값 증가분으로 더해요. */
    static final class Bar {
        final Instant start;
        final BigDecimal open;
        BigDecimal high, low, close, value = BigDecimal.ZERO;
        long volume;

        Bar(Instant start, BigDecimal price) { this.start = start; open = high = low = close = price; }

        void add(BigDecimal price, long volumeDelta, BigDecimal tradeValue) {
            high = high.max(price); low = low.min(price); close = price;
            volume += volumeDelta; value = value.add(tradeValue);
        }
    }

    final Pick pick;
    final Profile profile;
    private MarketTick last;
    private Instant lastReceivedAt;
    private Instant lastGapAt;
    private int volumeMismatches;
    private BigDecimal or5High, or15High, or15Low;
    private Bar minute, five;
    private final Deque<Bar> minutes = new ArrayDeque<>();
    private final Deque<Bar> fives = new ArrayDeque<>();

    StockMonitor(Pick pick, Profile profile) { this.pick = pick; this.profile = profile; }

    /**
     * 하는 일: 틱 하나로 지표를 갱신해요.
     * 순서가 뒤바뀐 틱과 누적 거래량이 늘지 않은 중복 틱은 버려요. 끊김(dataLoss, 장중에 처음 받은 틱)은 시각을 남겨요.
     * 누적 거래량 증가분이 이번 체결량과 다른 것(KIS가 체결 일부를 빼고 보냄)은 끊김으로 보지 않고 횟수만 세요.
     * 돌려주는 것: 반영했으면 true.
     */
    boolean accept(ObservedMarketTick event) {
        MarketTick tick = event.tick();
        LocalTime time = MarketSessionHours.regularTime(tick.occurredAt());
        if (time.isBefore(MarketSessionHours.REGULAR_OPEN) || !time.isBefore(WatchRules.SESSION_END))
            return false;
        if (last != null && (tick.occurredAt().isBefore(last.occurredAt()) || tick.cumulativeVolume() <= last.cumulativeVolume()))
            return false;
        if (event.dataLoss() || (last == null && !time.isBefore(LocalTime.of(9, 1))))
            lastGapAt = event.receivedAt();
        long volume = last == null ? tick.tradeVolume() : tick.cumulativeVolume() - last.cumulativeVolume();
        if (last != null && volume != tick.tradeVolume())
            volumeMismatches++;
        // 시가 범위: 틱의 당일 고가·저가는 그 순간까지의 범위라서, 범위가 끝나기 전 마지막 틱의 값이 곧 시가 범위예요.
        if (time.isBefore(WatchRules.OPENING_RANGE_5_END))
            or5High = max(or5High, tick.highPrice() != null ? tick.highPrice() : tick.price());
        if (time.isBefore(WatchRules.OPENING_RANGE_15_END)) {
            or15High = max(or15High, tick.highPrice() != null ? tick.highPrice() : tick.price());
            or15Low = min(or15Low, tick.lowPrice() != null ? tick.lowPrice() : tick.price());
        }
        BigDecimal value = last != null && tick.cumulativeTradingValue() != null && last.cumulativeTradingValue() != null
                && tick.cumulativeTradingValue().compareTo(last.cumulativeTradingValue()) >= 0
                ? tick.cumulativeTradingValue().subtract(last.cumulativeTradingValue())
                : tick.price().multiply(BigDecimal.valueOf(tick.tradeVolume()));
        Instant minuteStart = tick.occurredAt().truncatedTo(ChronoUnit.MINUTES);
        Instant fiveStart = fiveStart(tick.occurredAt());
        // 이미 닫혔어야 할 칸에 늦게 도착한 틱은 칸에 넣지 않아요(확정 칸을 덮어쓰지 않음). 가격 추적에는 써요.
        if (event.receivedAt().isBefore(minuteStart.plusSeconds(60).plus(WatchRules.BAR_WATERMARK))) {
            if (minute != null && minuteStart.isAfter(minute.start)) close(minutes, minute, 30);
            if (minute == null || minuteStart.isAfter(minute.start)) minute = new Bar(minuteStart, tick.price());
            minute.add(tick.price(), volume, value);
        }
        if (event.receivedAt().isBefore(fiveStart.plusSeconds(300).plus(WatchRules.BAR_WATERMARK))) {
            if (five != null && fiveStart.isAfter(five.start)) close(fives, five, 80);
            if (five == null || fiveStart.isAfter(five.start)) five = new Bar(fiveStart, tick.price());
            five.add(tick.price(), volume, value);
        }
        last = tick;
        lastReceivedAt = event.receivedAt();
        return true;
    }

    /**
     * 하는 일: 끝난 지 2초가 지난 1분·5분 칸을 닫아요.
     * 돌려주는 것: 이번에 1분 칸이 하나라도 닫혔으면 true(상태를 다시 판단할 때예요).
     */
    boolean closeBars(Instant now) {
        boolean closed = false;
        if (minute != null && !now.isBefore(minute.start.plusSeconds(60).plus(WatchRules.BAR_WATERMARK))) {
            close(minutes, minute, 30); minute = null; closed = true;
        }
        if (five != null && !now.isBefore(five.start.plusSeconds(300).plus(WatchRules.BAR_WATERMARK))) {
            close(fives, five, 80); five = null;
        }
        return closed;
    }

    private static void close(Deque<Bar> target, Bar bar, int keep) {
        target.addLast(bar);
        while (target.size() > keep) target.removeFirst();
    }

    static Instant fiveStart(Instant at) {
        var local = at.atZone(ZONE).truncatedTo(ChronoUnit.MINUTES);
        return local.minusMinutes(local.getMinute() % 5).toInstant();
    }

    MarketTick last() { return last; }
    Instant lastReceivedAt() { return lastReceivedAt; }
    int volumeMismatches() { return volumeMismatches; }
    Bar lastFive() { return fives.peekLast(); }
    Bar lastMinute() { return minutes.peekLast(); }
    BigDecimal or5High() { return or5High; }
    BigDecimal or15High() { return or15High; }
    BigDecimal or15Low() { return or15Low; }

    /** VWAP = 누적 거래대금 ÷ 누적 거래량(틱에 실린 당일 누적값). 없으면 null. */
    BigDecimal vwap() {
        if (last == null || last.cumulativeVolume() <= 0 || last.cumulativeTradingValue() == null) return null;
        return last.cumulativeTradingValue().divide(BigDecimal.valueOf(last.cumulativeVolume()), 4, RoundingMode.HALF_UP);
    }

    /** ATR5m = 최근 완료 5분봉(최대 12개)의 고가−저가 평균. 3개 미만이면 null. */
    BigDecimal atr() {
        if (fives.size() < WatchRules.ATR_MIN_BARS) return null;
        BigDecimal sum = BigDecimal.ZERO; int count = 0;
        var iterator = fives.descendingIterator();
        while (iterator.hasNext() && count < WatchRules.ATR_BARS) {
            Bar bar = iterator.next(); sum = sum.add(bar.high.subtract(bar.low)); count++;
        }
        return sum.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
    }

    /** RVOL_5M = 마지막 완료 5분봉 거래량 ÷ 같은 칸 과거 평균. 과거 일수가 모자라거나 평균이 0이면 null. */
    BigDecimal rvol() {
        Bar bar = lastFive();
        if (bar == null || profile.days() < WatchRules.RVOL_MIN_DAYS) return null;
        BigDecimal average = profile.slotVolume().get(bar.start.atZone(ZONE).toLocalTime());
        if (average == null || average.signum() <= 0) return null;
        return BigDecimal.valueOf(bar.volume).divide(average, 4, RoundingMode.HALF_UP);
    }

    /**
     * 자료 품질: 마지막 틱 수신이 60초 안이고, 최근 5분 안에 끊김이 없어야 해요.
     */
    boolean dataOk(Instant now) {
        return lastReceivedAt != null && Duration.between(lastReceivedAt, now).compareTo(WatchRules.DATA_MAX_SILENCE) <= 0
                && (lastGapAt == null || Duration.between(lastGapAt, now).compareTo(WatchRules.DATA_GAP_LOOKBACK) > 0);
    }

    /** 최근 5분 안에 끊김(dataLoss·장중 첫 수신)이 있었는지예요. 조용한 것(체결 없음)과는 달라요. */
    boolean recentGap(Instant now) {
        return lastGapAt != null && Duration.between(lastGapAt, now).compareTo(WatchRules.DATA_GAP_LOOKBACK) <= 0;
    }

    private static BigDecimal max(BigDecimal a, BigDecimal b) { return a == null ? b : a.max(b); }
    private static BigDecimal min(BigDecimal a, BigDecimal b) { return a == null ? b : a.min(b); }
}
