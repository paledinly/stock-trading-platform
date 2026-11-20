/*
 * 화면에서 종목 한 개의 하루 가격 흐름(1분봉)을 볼 때 쓰는 조회 서비스예요.
 * 실시간 구독이 없어도 KIS 분봉 조회로 받아요. 오늘은 당일 분봉(30분씩), 지난 날은 일별 분봉(120분씩)을 거꾸로 이어 붙여요.
 * 표시 전용이라 DB에 저장하지 않아요. 실시간으로 모은 봉(stock_candle)과 섞지 않고, 추천·감시·채점에도 쓰지 않아요.
 */
package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.closing.application.MarketSessionHours;
import com.sunmo.stockplatform.kis.candle.KisMinuteCandleClient;
import com.sunmo.stockplatform.kis.candle.MinuteCandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;

/** 종목·날짜 하나의 1분봉 흐름을 KIS에서 받아 짧게 기억해 두는 서비스예요. */
@Service
public class IntradayFlowService {
    private static final Logger log = LoggerFactory.getLogger(IntradayFlowService.class);
    // 오늘 흐름은 1분 동안만 기억해요(새 분봉이 계속 생겨요). 지난 날 흐름은 바뀌지 않아서 계속 기억해요.
    static final Duration TODAY_TTL = Duration.ofSeconds(60);
    static final int CACHE_LIMIT = 300;
    // 하루 장(6시간 반 + 특별 개장 연장)을 넉넉히 덮는 호출 횟수 상한이에요.
    private static final int MAX_CALLS = 20;

    private final KisMinuteCandleClient kis;
    private final ClosingTradingCalendar calendar;
    private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);

    public IntradayFlowService(KisMinuteCandleClient kis, ClosingTradingCalendar calendar) {
        this.kis = kis;
        this.calendar = calendar;
    }

    /** 분봉 하나예요. time은 서울 시각 "HH:mm", 가격은 원, volume은 그 분의 체결량이에요. */
    public record Bar(String time, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) { }

    /**
     * 하루 흐름 응답이에요. status: OK(분봉 있음), EMPTY(받은 분봉 없음), NOT_STARTED(오늘 장 시작 전),
     * FUTURE(아직 오지 않은 날), HOLIDAY(거래일 아님), UNAVAILABLE(KIS 조회 실패).
     * changeFromOpenPct는 첫 분봉 시가 대비 마지막 분봉 종가 등락이에요(후보 평가 채점의 시가→종가와 같은 기준).
     */
    public record Flow(String stockCode, LocalDate date, String status, String message, boolean live, Instant fetchedAt,
            BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal last, Double changeFromOpenPct, long volume,
            List<Bar> bars) { }

    private record Cached(Flow flow, Instant at) { }

    /** 하는 일: 종목의 그날 1분봉 흐름을 돌려줘요. 같은 종목·날짜는 기억해 둔 값을 먼저 써요. */
    public synchronized Flow flow(String stockCode, LocalDate date) {
        Instant now = calendar.now();
        ZonedDateTime local = now.atZone(MarketSessionHours.ZONE);
        LocalDate today = local.toLocalDate();
        if (date.isAfter(today))
            return empty(stockCode, date, "FUTURE", "아직 오지 않은 날이에요. 그날 장이 열리면 볼 수 있어요.", now);
        if (!calendar.isTradingDay(date))
            return empty(stockCode, date, "HOLIDAY", "거래일이 아니에요.", now);
        LocalTime open = MarketSessionHours.open(date), close = MarketSessionHours.close(date);
        boolean isToday = date.equals(today);
        if (isToday && local.toLocalTime().isBefore(open))
            return empty(stockCode, date, "NOT_STARTED", "오늘 장이 아직 시작되지 않았어요(" + open + " 시작).", now);
        boolean live = isToday && local.toLocalTime().isBefore(close.plusMinutes(1));
        String key = stockCode + "|" + date;
        Cached hit = cache.get(key);
        if (hit != null && (!live || Duration.between(hit.at(), now).compareTo(TODAY_TTL) < 0))
            return hit.flow();
        Flow flow;
        try {
            LocalTime through = live ? local.toLocalTime().withNano(0) : close;
            List<MinuteCandle> candles = isToday
                    ? collect(open, through, (at, ignored) -> kis.fetch(stockCode, at))
                    : collect(open, through, (at, ignored) -> kis.fetchHistory(stockCode, date, at));
            flow = build(stockCode, date, live, now, open, close, candles);
        } catch (RuntimeException failure) {
            log.warn("Intraday flow unavailable for {} {} ({})", stockCode, date, failure.getClass().getSimpleName());
            return empty(stockCode, date, "UNAVAILABLE", "KIS 분봉 조회에 실패했어요. 잠시 뒤 다시 열어 주세요.", now);
        }
        // 지난 날인데 비어 있으면 KIS가 그 날짜를 주지 않는 경우라 기억하지 않아요(나중에 다시 시도).
        if (!"EMPTY".equals(flow.status()) || live) {
            cache.put(key, new Cached(flow, now));
            if (cache.size() > CACHE_LIMIT) cache.remove(cache.keySet().iterator().next());
        }
        return flow;
    }

    /**
     * 하는 일: 지정 시각에서 거꾸로 분봉을 받아 장 시작까지 이어 붙여요.
     * 한 번 받은 것 중 가장 이른 분봉 1분 전부터 다시 받아요. 더 이른 봉이 안 오면 멈춰요.
     */
    static List<MinuteCandle> collect(LocalTime open, LocalTime through,
            BiFunction<LocalTime, Integer, List<MinuteCandle>> fetch) {
        TreeMap<Instant, MinuteCandle> bars = new TreeMap<>();
        LocalTime at = through;
        for (int call = 0; call < MAX_CALLS; call++) {
            List<MinuteCandle> page = fetch.apply(at, call);
            Instant earliest = page.stream().map(MinuteCandle::startTime).min(Comparator.naturalOrder()).orElse(null);
            if (earliest == null || (!bars.isEmpty() && !earliest.isBefore(bars.firstKey()))) break;
            page.forEach(candle -> bars.putIfAbsent(candle.startTime(), candle));
            LocalTime first = earliest.atZone(MarketSessionHours.ZONE).toLocalTime();
            if (!first.isAfter(open)) break;
            at = first.minusMinutes(1);
        }
        return new ArrayList<>(bars.values());
    }

    private static Flow build(String code, LocalDate date, boolean live, Instant now, LocalTime open, LocalTime close,
            List<MinuteCandle> candles) {
        List<Bar> bars = new ArrayList<>();
        for (MinuteCandle c : candles) {
            ZonedDateTime at = c.startTime().atZone(MarketSessionHours.ZONE);
            LocalTime time = at.toLocalTime();
            // 그 날짜 정규장 안의 봉만 써요(시간외 단일가·전날 봉 제외).
            if (!at.toLocalDate().equals(date) || time.isBefore(open) || time.isAfter(close)) continue;
            bars.add(new Bar(time.toString().substring(0, 5), c.open(), c.high(), c.low(), c.close(), c.volume()));
        }
        if (bars.isEmpty())
            return new Flow(code, date, "EMPTY", "받은 분봉이 없어요(거래가 없었거나 KIS가 그 날짜 분봉을 주지 않아요).", live,
                    now, null, null, null, null, null, 0, List.of());
        BigDecimal first = bars.get(0).open(), last = bars.get(bars.size() - 1).close();
        BigDecimal high = bars.stream().map(Bar::high).max(Comparator.naturalOrder()).orElseThrow();
        BigDecimal low = bars.stream().map(Bar::low).min(Comparator.naturalOrder()).orElseThrow();
        long volume = bars.stream().mapToLong(Bar::volume).sum();
        Double change = first.signum() > 0
                ? last.subtract(first).multiply(BigDecimal.valueOf(100)).divide(first, 4, RoundingMode.HALF_UP).doubleValue()
                : null;
        return new Flow(code, date, "OK", live ? "장중이에요. 1분마다 새로 받아요." : null, live, now, first, high, low, last,
                change, volume, bars);
    }

    private static Flow empty(String code, LocalDate date, String status, String message, Instant now) {
        return new Flow(code, date, status, message, false, now, null, null, null, null, null, 0, List.of());
    }
}
