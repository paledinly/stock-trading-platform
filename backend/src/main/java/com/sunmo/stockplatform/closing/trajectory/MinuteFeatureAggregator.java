/*
 * 실시간 체결 틱을 종목별 1분 집계(시가·고가·저가·종가, 거래량·거래대금, 매수/매도 체결량 등)로 메모리에서 묶는 집계기예요.
 * 틱을 영구 저장하지 않고, 확정된 1분은 다시 고치지 않아요. 09:00~15:20 미만 정규장 체결만 다뤄요.
 * TrajectoryService가 만들어 틱을 넘기고, 주기적으로 확정분을 꺼내 DB에 저장한 뒤 확인(acknowledge)해요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import com.sunmo.stockplatform.market.domain.MarketTick;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.*;

/** Bounded minute aggregates, never a tick journal. Finalized buckets cannot be revised. */
/*
 * (크기가 제한된 1분 집계일 뿐 틱 일지가 아니에요. 확정된 칸은 고칠 수 없어요.)
 * 비유하면 1분짜리 모래시계 바구니예요. 1분 동안 들어온 체결을 한 바구니에 담고, 1분+대기시간(watermark)이 지나면 뚜껑을 닫아요.
 * 뚜껑 닫힌 바구니에 늦게 온 틱은 넣지 않고 "거부" 개수만 세요.
 */
public class MinuteFeatureAggregator {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private final Duration watermark;
    private final Map<String, State> states = new HashMap<>();
    private LocalDate date;
    private long rejected;
    /** 하는 일: 늦은 틱을 얼마나 기다릴지(watermark)를 정해 집계기를 만들어요. 받는 것: 대기시간. 돌려주는 것: 집계기예요. */
    public MinuteFeatureAggregator(Duration watermark) { this.watermark = watermark; }

    /**
     * 하는 일: 체결 틱 하나를 해당 종목·분 바구니에 더해요.
     * 받는 것: 수신시각이 붙은 체결 이벤트예요. 돌려주는 것: 없어요.
     * 원천 발생시각이 수신시각보다 미래이거나, 날짜가 지났거나, 이미 닫힌 분이거나, 순서가 뒤바뀐 틱은 거부해요.
     */
    public synchronized void accept(ObservedMarketTick event) {
        MarketTick tick = event.tick();
        // 영업일과 수신 날짜가 다르거나 발생시각이 수신시각보다 늦은(미래) 틱은 믿을 수 없어 거부해요.
        if (!tick.businessDate().equals(event.receivedAt().atZone(ZONE).toLocalDate())
                || tick.occurredAt().isAfter(event.receivedAt())) { rejected++; return; }
        if (date != null && tick.businessDate().isBefore(date)) { rejected++; return; }
        // 새 영업일이 시작되면 이전 날 상태를 모두 비워요.
        if (!tick.businessDate().equals(date)) { states.clear(); date = tick.businessDate(); }
        LocalTime time = tick.occurredAt().atZone(ZONE).toLocalTime();
        if (time.isBefore(LocalTime.of(9, 0)) || !time.isBefore(LocalTime.of(15, 20))) return;
        Instant start = tick.occurredAt().truncatedTo(ChronoUnit.MINUTES);
        // 분 끝+대기시간 이후에 도착한 틱은 이미 확정됐을 분이라 넣지 않아요(늦은 틱으로 확정봉을 덮어쓰지 않음).
        if (!event.receivedAt().isBefore(start.plusSeconds(60).plus(watermark))) { rejected++; return; }
        State state = states.computeIfAbsent(tick.stockCode(), ignored -> new State());
        MarketTick previous = state.last;
        if ((state.closedThrough != null && !start.isAfter(state.closedThrough))
                || (previous != null && (tick.occurredAt().isBefore(previous.occurredAt())
                    || tick.cumulativeVolume() <= previous.cumulativeVolume()))) { rejected++; return; }
        Bucket bucket = state.buckets.computeIfAbsent(start, ignored -> new Bucket(tick, event.receivedAt()));
        // 직전 틱과 1분 이내로 이어지고, 누적 거래량 증가분이 이번 체결량과 정확히 같아야 "빠짐없이 받았다"고 봐요.
        // 한 번이라도 어긋나면 그 분은 complete=false가 돼요(첫 틱이 없는 첫 분도 마찬가지예요).
        boolean continuous = !event.dataLoss() && previous != null
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
    /*
     * (호출자는 DB 커밋 후에만 확인하므로, 일시적 DB 실패 때 같은 집계를 다시 저장할 수 있어요.)
     * 하는 일: 분 끝+대기시간이 지난 바구니를 1분 집계(Minute)로 만들어 돌려주고, 그 분까지를 "닫힘"으로 표시해요.
     * 받는 것: 현재 시각이에요. 돌려주는 것: 확정된 Minute 목록이에요. 거래대금 증분을 모르면 complete=false예요.
     */
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
    /** 하는 일: DB 저장이 끝난 1분 집계를 메모리 바구니에서 지워요. 받는 것: 저장된 Minute 목록. 돌려주는 것: 없어요. */
    public synchronized void acknowledge(List<Minute> rows) {
        for (Minute row : rows) {
            State state = states.get(row.symbol());
            if (state != null) state.buckets.remove(row.start());
        }
    }
    /** 하는 일: 지금까지 거부했거나 버린 틱·분 개수를 돌려줘요. */
    public synchronized long rejected() { return rejected; }
    /**
     * 하는 일: 분 시작 시각으로 장 구간 이름을 정해요. 14:30 전 REGULAR, 14:30~15:00 PHASE_1, 15:00~15:10 PHASE_2, 그 뒤 PHASE_3이에요.
     * 받는 것: 분 시작 시각. 돌려주는 것: 구간 이름이에요.
     */
    static String phase(Instant start) {
        LocalTime t = start.atZone(ZONE).toLocalTime();
        return t.isBefore(LocalTime.of(14, 30)) ? "REGULAR" : t.isBefore(LocalTime.of(15, 0)) ? "CLOSING_PHASE_1"
                : t.isBefore(LocalTime.of(15, 10)) ? "CLOSING_PHASE_2" : "CLOSING_PHASE_3";
    }
    /** 작은 계산 도우미예요. number: Long을 BigDecimal로(null 유지), delta: a-b(어느 쪽이 없거나 줄었으면 null), add: 둘 다 있을 때만 더하기. */
    private static BigDecimal number(Long n) { return n == null ? null : BigDecimal.valueOf(n); }
    private static BigDecimal delta(BigDecimal a, BigDecimal b) { return a == null || b == null || a.compareTo(b) < 0 ? null : a.subtract(b); }
    private static BigDecimal add(BigDecimal a, BigDecimal b) { return a == null || b == null ? null : a.add(b); }
    /** 종목 하나의 상태예요. 마지막 틱, 닫힌 마지막 분, 아직 저장 안 된 분 바구니들(시간순)을 담아요. */
    private static class State {
        MarketTick last; Instant closedThrough; TreeMap<Instant, Bucket> buckets = new TreeMap<>();
    }
    /** 1분 바구니 하나예요. 시가·고가·저가, 거래량·거래대금, 매수·매도 체결량, 마지막 틱·수신시각, 온전함 여부를 담아요. */
    private static class Bucket {
        BigDecimal open, high, low, volume = BigDecimal.ZERO, turnover = BigDecimal.ZERO,
                buy = BigDecimal.ZERO, sell = BigDecimal.ZERO;
        MarketTick last; Instant received; boolean complete = true;
        Bucket(MarketTick t, Instant receipt) { open = high = low = t.price(); last = t; received = receipt; }
    }
}
