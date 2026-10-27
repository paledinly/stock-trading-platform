/*
 * 실시간 틱(체결 한 건)을 모아 종목별 5분봉을 만드는 집계기예요.
 * 정규장 칸은 09:00~15:25이고, 15:30 종가 단일가 체결(랜덤엔드로 최대 15:30:30, 여유 포함 15:31:00까지)은 마지막 15:25 봉에 합쳐요.
 * 다음 칸의 틱이 오거나 시간이 지나면(봉 끝 + watermark) 봉을 확정하고, 확정 뒤 늦게 온 틱으로 확정봉을 다시 만들지 않아요.
 * MarketDataService가 틱마다 accept, 1초마다 flush를 부르고, CandleBackfillService·RealtimeSessionPolicy가 시간 규칙을 함께 써요.
 */
package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.closing.application.MarketSessionHours;
import com.sunmo.stockplatform.market.domain.MarketTick;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 종목별 5분봉 계산대예요.
 * 5분짜리 모래시계를 종목마다 하나씩 두고, 그 안에 들어온 체결로 시가·고가·저가·종가·거래량을 적다가
 * 다음 모래시계가 시작되거나 정해진 시간이 지나면 기록지를 확정해 넘겨줘요.
 * 확정이 끝난 칸(finalizedThrough)보다 이전 시각의 틱은 받지 않아요.
 */
public class FiveMinuteCandleAggregator {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /**
     * KRX closes with a 15:20-15:30 single-price call whose end is randomly extended by up to 30 seconds,
     * so the one closing execution is stamped 15:30:00..15:30:30. This window (with margin) is the only
     * post-15:30 time accepted as regular-session data; after-hours sessions start at 15:40.
     * 한국거래소는 15:20~15:30 종가 단일가 접수 후 15:30에 한 번 체결하는데, 랜덤엔드로 최대 30초 늦어질 수 있어요.
     * 그래서 15:30 이후 60초(여유 포함)까지만 정규장 데이터로 받고, 15:40부터인 시간외 거래는 받지 않아요.
     */
    public static final Duration CLOSING_AUCTION_WINDOW = Duration.ofSeconds(60);
    private static final Duration BUCKET = Duration.ofMinutes(5);
    private final Duration watermark;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, Instant> finalizedThrough = new ConcurrentHashMap<>();

    /**
     * 하는 일: 봉 끝난 뒤 늦은 틱을 얼마나 기다렸다 확정할지(watermark)를 정해요.
     * 받는 것: watermark 시간(설정 MARKET_CANDLE_WATERMARK, 기본 2초).
     */
    public FiveMinuteCandleAggregator(Duration watermark) {
        this.watermark = watermark;
    }

    /**
     * 하는 일: 틱 하나를 해당 종목의 진행 중 봉에 반영해요.
     * 받는 것: 틱(정규장 시간 안이어야 해요. 밖이면 bucketStart가 예외를 던져요).
     * 돌려주는 것: 바뀐 봉 스냅샷 목록. 비어 있으면 무시된 틱이고,
     * 새 칸이 시작되면 [직전 봉 확정본, 새 봉 진행본] 두 개가 나와요.
     */
    public synchronized List<CandleSnapshot> accept(MarketTick tick) {
        Instant bucket = bucketStart(tick.occurredAt());
        State state = states.get(tick.stockCode());
        List<CandleSnapshot> result = new ArrayList<>();
        // 진행 중 봉이 없을 때: 이미 확정한 칸 이하의 늦은 틱이면 버리고, 아니면 새 봉을 시작해요.
        if (state == null) {
            Instant finalized = finalizedThrough.get(tick.stockCode());
            if (finalized != null && !bucket.isAfter(finalized))
                return result;
            states.put(tick.stockCode(), State.first(tick, bucket));
            result.add(states.get(tick.stockCode()).snapshot(false));
            return result;
        }
        // 다음 칸의 틱이 왔을 때: 지금 봉을 바로 확정하고 새 봉을 시작해요.
        if (bucket.isAfter(state.start)) {
            finalizedThrough.put(tick.stockCode(), state.start);
            result.add(state.snapshot(true));
            state = State.first(tick, bucket);
            states.put(tick.stockCode(), state);
            result.add(state.snapshot(false));
            return result;
        }
        // 이전 칸의 늦은 틱은 무시해요(확정봉을 부분 데이터로 다시 만들지 않기 위해).
        if (bucket.isBefore(state.start))
            return result;
        // 같은 칸의 틱이면 봉에 반영하고, 실제로 바뀌었을 때만 진행본을 내보내요.
        if (bucket.equals(state.start)) {
            if (state.apply(tick))
                result.add(state.snapshot(false));
        }
        return result;
    }

    /**
     * 하는 일: 봉 끝 시각 + watermark가 지난 진행 중 봉을 모두 확정하고 목록에서 빼요.
     * 다음 틱이 오지 않는 종목(거래가 뜸한 종목, 장 마지막 봉)도 확정되게 하기 위해 주기적으로 불려요.
     * 받는 것: 현재 시각. 돌려주는 것: 이번에 확정된 봉 목록.
     */
    public synchronized List<CandleSnapshot> flush(Instant now) {
        List<CandleSnapshot> closed = new ArrayList<>();
        Iterator<Map.Entry<String, State>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            State state = iterator.next().getValue();
            if (!now.isBefore(bucketEnd(state.start).plus(watermark))) {
                finalizedThrough.put(state.code, state.start);
                closed.add(state.snapshot(true));
                iterator.remove();
            }
        }
        return closed;
    }

    /**
     * Regular buckets start 09:00..15:25; the closing auction print (15:30:00 + window) belongs to 15:25.
     * 하는 일: 체결시각이 속한 5분 칸의 시작시각을 구해요. 15:30:00~15:31:00 종가 체결은 15:25 칸으로 보내요.
     * 받는 것: 원천 체결시각. 돌려주는 것: 칸 시작시각.
     * 09:00 이전이나 15:31:00 이후면 IllegalArgumentException을 던져요.
     * 수능일처럼 시간이 바뀐 날은 그날 개장·마감(MarketSessionHours)을 기준으로 해요(예: 10:00~16:25 칸, 16:30 종가 → 16:25 칸).
     */
    public static Instant bucketStart(Instant instant) {
        ZonedDateTime time = instant.atZone(SEOUL);
        var date = time.toLocalDate();
        ZonedDateTime open = date.atTime(MarketSessionHours.open(date)).atZone(SEOUL);
        ZonedDateTime close = date.atTime(MarketSessionHours.close(date)).atZone(SEOUL);
        if (time.isBefore(open) || time.isAfter(close.plus(CLOSING_AUCTION_WINDOW)))
            throw new IllegalArgumentException("Tick outside regular KRX session");
        if (!time.isBefore(close))
            return close.minus(BUCKET).toInstant();
        long minutes = ChronoUnit.MINUTES.between(open, time);
        return open.plusMinutes((minutes / 5) * 5).toInstant();
    }

    /**
     * The final 15:25 bucket stays open until the closing auction window has passed.
     * 하는 일: 칸의 끝 시각을 구해요. 보통은 시작 + 5분이지만, 15:25 칸은 종가 체결을 기다리려고 15:31:00까지 열어 둬요.
     * 받는 것: 칸 시작시각. 돌려주는 것: 칸 끝 시각.
     */
    static Instant bucketEnd(Instant start) {
        Instant end = start.plus(BUCKET);
        ZonedDateTime local = end.atZone(SEOUL);
        return local.toLocalTime().equals(MarketSessionHours.close(local.toLocalDate())) ? end.plus(CLOSING_AUCTION_WINDOW) : end;
    }

    /**
     * 종목 하나의 진행 중 봉 기록지예요. 마지막으로 반영한 순번·시각·누적값을 기억해 중복·역순 틱을 걸러요.
     */
    private static final class State {
        String code;
        Instant start;
        BigDecimal open, high, low, close, value;
        long volume, lastCum;
        BigDecimal lastCumValue;
        Instant lastEvent;
        long lastSequence;
        // 지금은 revision을 올리는 코드가 없어 스냅샷의 revision은 항상 0이에요. 확정봉은 늦은 틱으로 다시 고치지 않으므로
        // (finalizedThrough 이하의 틱은 버려요) 한 봉이 여러 판을 갖지 않기 때문이에요. 만약 나중에 확정 뒤 정정을
        // 허용한다면, 이 값은 같은 시작시각의 확정봉이 몇 번째로 고쳐진 판인지(0 = 처음 확정본)를 뜻하게 돼요.
        // 저장(stock_candle.revision)과 SSE payload는 이 값을 그대로 옮기기만 해요.
        int revision;

        /**
         * 하는 일: 칸의 첫 틱으로 새 기록지를 만들어요. 시·고·저·종가는 모두 첫 체결가예요.
         * 받는 것: 첫 틱, 칸 시작시각. 돌려주는 것: 새 State.
         */
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

        /**
         * 하는 일: 같은 칸의 틱을 기록지에 반영해요.
         * 받는 것: 틱. 돌려주는 것: 반영했으면 true, 중복·역순 틱이라 무시했으면 false.
         */
        boolean apply(MarketTick tick) {
            // 이미 본 순번, 더 이른 시각, 같은 시각인데 누적값이 늘지 않은 틱은 중복으로 보고 버려요.
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
            // 거래량·거래대금은 누적값의 차이로 더하고, 누적값이 줄었거나 없으면 이번 틱 체결량(가격 × 수량)으로 대신해요.
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

        /** 하는 일: 지금 기록지를 스냅샷으로 찍어요. 받는 것: 확정 여부. 돌려주는 것: CandleSnapshot. */
        CandleSnapshot snapshot(boolean done) {
            return new CandleSnapshot(code, start, open, high, low, close, volume, value, done, revision);
        }
    }
}
