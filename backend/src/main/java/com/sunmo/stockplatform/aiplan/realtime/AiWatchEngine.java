/*
 * AI 장전 계획 2단계의 판단 본체예요. 하루 한 번 만들어지고, 틱과 1분 시계로 종목 상태를 정하고 가상 진입·청산을 기록해요.
 * 규칙 P(프로그램 고정 규칙)와 규칙 A(AI가 적은 진입 조건)를 같은 종목에 동시에 적용해 따로 기록해요.
 * DB·KIS·Spring을 모르는 순수 계산이라 테스트에서 합성 틱으로 그대로 재현할 수 있어요. 결과는 Effect 목록으로 돌려줘요.
 * 실제 주문은 없어요. 계획이 TRADE가 아닌 날(감시만·매매 안 함·자료 부족)의 진입은 shadow로 표시해요.
 */
package com.sunmo.stockplatform.aiplan.realtime;

import com.sunmo.stockplatform.aiplan.realtime.WatchRules.Check;
import com.sunmo.stockplatform.aiplan.realtime.WatchRules.State;
import com.sunmo.stockplatform.closing.application.MarketSessionHours;
import com.sunmo.stockplatform.closing.config.TradingCostProperties;
import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import com.sunmo.stockplatform.market.domain.MarketTick;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 하루치 실시간 감시 판단기예요. 모든 공개 메서드는 synchronized라 틱 스레드와 스케줄러 스레드가 함께 불러도 돼요. */
public final class AiWatchEngine {
    public static final String RULE_P = "P";
    public static final String RULE_A = "A";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    // 진입 대기(호가 조회 뒤)가 이 시간 안에 체결을 못 만나면 취소해요. 다음 1분 판단에서 다시 신호를 낼 수 있어요.
    static final Duration PENDING_TIMEOUT = Duration.ofSeconds(60);

    /** 판단 결과 하나예요. 서비스가 DB에 저장하고 화면에 알려요. */
    public sealed interface Effect permits SignalChanged, EntryRequested, PositionChanged { }

    /** 상태가 바뀌었어요. 원천 체결시각(sourceAt)·수신시각(receivedAt)·평가시각(evaluatedAt)을 따로 담아요. */
    public record SignalChanged(long pickId, String stockCode, String rule, State from, State to, List<String> reasons,
            Map<String, Object> indicators, Instant sourceAt, Instant receivedAt, Instant evaluatedAt) implements Effect { }

    /** 매수 신호가 났어요. 서비스가 호가를 한 번 조회해 arm()을 불러야 다음 체결에서 가상 진입해요. */
    public record EntryRequested(String stockCode, String rule) implements Effect { }

    /** 가상 포지션이 열렸거나(opened) 청산 값이 바뀌었어요. */
    public record PositionChanged(PositionView position, boolean opened) implements Effect { }

    /** 가상 포지션 한 건의 사본이에요. 금액 단위는 원, 수익률은 비용을 뺀 %예요. */
    public record PositionView(long pickId, String stockCode, String rule, boolean shadow, Instant signalAt,
            Instant entrySourceAt, Instant entryReceivedAt, BigDecimal entryTickPrice, BigDecimal halfSpreadPct,
            String spreadSource, BigDecimal entryPrice, long quantity, boolean capacityLimited, BigDecimal stopPrice,
            Exit e1, Exit e2, BigDecimal maxPrice, BigDecimal minPrice, BigDecimal dayOpenPrice, boolean trackingGap) { }

    /** 청산 하나예요. reason: STOP(손절), TAKE(익절), CLOSE(15:19 마감 청산). */
    public record Exit(BigDecimal price, Instant at, String reason, BigDecimal netPct) { }

    /** 화면에 보여 줄 종목 하나의 현재 모습이에요. */
    public record StockView(long pickId, String stockCode, String stockName, Integer priority, boolean topPriority,
            boolean subscribed, State stateP, State stateA, Map<String, Object> indicators, Map<String, Check> checksP,
            Map<String, Check> checksA, List<String> unsupportedA, List<PositionView> positions) { }

    private final LocalDate date;
    private final boolean shadow;
    private final BigDecimal amount;
    private final TradingCostProperties costs;
    private final Map<String, Watch> watches = new LinkedHashMap<>();
    private final Map<String, IndexValue> market = new HashMap<>();
    private Instant lastEvaluatedMinute;
    private boolean closedOut;

    /**
     * 받는 것: 거래일, 그날 계획의 판단(TRADE가 아니면 진입을 shadow로 기록), 가상 1회 금액(원), 비용 설정.
     */
    public AiWatchEngine(LocalDate date, String tradeDecision, BigDecimal amount, TradingCostProperties costs) {
        this.date = date;
        this.shadow = !"TRADE".equals(tradeDecision);
        this.amount = amount;
        this.costs = costs;
    }

    public LocalDate date() { return date; }

    /** 하는 일: 감시 종목을 더해요. 구독하지 못한 종목은 NOT_SUBSCRIBED로 두고 판단하지 않아요. */
    public synchronized void add(StockMonitor.Pick pick, StockMonitor.Profile profile, boolean subscribed,
            List<String> unsupportedA) {
        Watch watch = new Watch(new StockMonitor(pick, profile), subscribed, unsupportedA);
        State initial = subscribed ? State.WATCH : State.NOT_SUBSCRIBED;
        watch.states.put(RULE_P, initial);
        watch.states.put(RULE_A, subscribed && !pick.conditionsSupported() ? State.UNSUPPORTED : initial);
        watches.put(pick.stockCode(), watch);
    }

    public synchronized Set<String> codes() { return Set.copyOf(watches.keySet()); }

    /** 하는 일: 재시작 뒤 DB에 남은 상태를 되살려요. INVALIDATED·EXPIRED는 그날 다시 풀리지 않아요. */
    public synchronized void restoreState(String code, String rule, State state) {
        Watch watch = watches.get(code);
        if (watch == null || watch.states.get(rule) == State.NOT_SUBSCRIBED) return;
        watch.states.put(rule, state);
        if (state == State.INVALIDATED) watch.invalidated = true;
    }

    /** 하는 일: 재시작 뒤 DB에 남은 가상 포지션을 되살려요. 재시작 사이 가격을 못 봤으므로 trackingGap을 켜요. */
    public synchronized void restore(PositionView view) {
        Watch watch = watches.get(view.stockCode());
        if (watch == null) return;
        Position position = new Position(view);
        position.trackingGap = true;
        watch.positions.put(view.rule(), position);
    }

    /** 하는 일: 지수 값을 받아요. open이 없으면 그날 처음 받은 값을 시가로 써요. */
    public synchronized void market(String name, BigDecimal value, BigDecimal open, Instant at) {
        IndexValue previous = market.get(name);
        BigDecimal base = open != null && open.signum() > 0 ? open : previous != null ? previous.open : value;
        market.put(name, new IndexValue(value, base, at));
    }

    /**
     * 하는 일: 감시 종목의 틱을 받아 지표를 갱신하고, 대기 중인 진입과 열린 포지션의 손절·익절을 확인해요.
     * 거래정지 틱이면 상태를 바로 CAUTION으로 바꿔요(1분을 기다리지 않음).
     */
    public synchronized List<Effect> accept(ObservedMarketTick event) {
        Watch watch = watches.get(event.tick().stockCode());
        if (watch == null || !watch.subscribed || !watch.monitor.accept(event)) return List.of();
        List<Effect> effects = new ArrayList<>();
        MarketTick tick = event.tick();
        if (tick.tradingHalted()) {
            for (String rule : List.of(RULE_P, RULE_A)) {
                State from = watch.states.get(rule);
                if (from != State.CAUTION && from != State.INVALIDATED && from != State.EXPIRED && from != State.UNSUPPORTED)
                    effects.add(change(watch, rule, State.CAUTION, List.of("HALTED"), Map.of(), event.receivedAt()));
            }
            return effects;
        }
        if (closedOut) return effects;
        for (var iterator = watch.pending.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            Pending pending = entry.getValue();
            if (pending.armedAt == null || !event.receivedAt().isAfter(pending.armedAt)) continue;
            iterator.remove();
            Position position = open(watch, entry.getKey(), pending, event);
            watch.positions.put(entry.getKey(), position);
            effects.add(new PositionChanged(position.view(), true));
        }
        for (Position position : watch.positions.values())
            if (position.track(tick, costs)) effects.add(new PositionChanged(position.view(), false));
        return effects;
    }

    /**
     * 하는 일: 호가 조회를 마친 매수 신호를 진입 대기로 바꿔요. 이 시각 뒤 처음 들어온 체결 가격으로 가상 진입해요.
     * 받는 것: 종목, 규칙, 스프레드 절반(%), 출처(ORDERBOOK·FALLBACK), 대기 시작 시각.
     */
    public synchronized void arm(String code, String rule, BigDecimal halfSpreadPct, String spreadSource, Instant armedAt) {
        Watch watch = watches.get(code);
        Pending pending = watch == null ? null : watch.pending.get(rule);
        if (pending == null || pending.armedAt != null) return;
        pending.halfSpreadPct = halfSpreadPct;
        pending.spreadSource = spreadSource;
        pending.armedAt = armedAt;
    }

    /**
     * 하는 일: 1분마다(분이 끝나고 2초 뒤) 모든 종목의 상태를 다시 판단해요. 15:19가 지나면 남은 포지션을 마감 청산해요.
     * 같은 분에는 한 번만 판단해요. 돌려주는 것: 상태 변화·매수 신호·청산 결과.
     */
    public synchronized List<Effect> evaluate(Instant now) {
        List<Effect> effects = new ArrayList<>();
        watches.values().forEach(watch -> watch.monitor.closeBars(now));
        LocalTime time = MarketSessionHours.regularTime(now);
        if (!closedOut && !time.isBefore(WatchRules.EXIT_AT)) {
            closedOut = true;
            for (Watch watch : watches.values()) {
                watch.pending.clear();
                MarketTick last = watch.monitor.last();
                for (Position position : watch.positions.values())
                    if (last != null && position.closeOut(last, costs)) effects.add(new PositionChanged(position.view(), false));
            }
        }
        Instant minute = now.minus(WatchRules.BAR_WATERMARK).truncatedTo(ChronoUnit.MINUTES);
        if (minute.equals(lastEvaluatedMinute)) return effects;
        lastEvaluatedMinute = minute;
        if (time.isBefore(MarketSessionHours.REGULAR_OPEN) || !time.isBefore(WatchRules.SESSION_END)) return effects;
        for (Watch watch : watches.values()) {
            watch.pending.values().removeIf(p -> p.armedAt != null
                    && Duration.between(p.armedAt, now).compareTo(PENDING_TIMEOUT) > 0);
            if (watch.subscribed) evaluate(watch, now, time, effects);
        }
        return effects;
    }

    /** 하는 일: 한 종목의 규칙 P·A 상태를 판단해요. */
    private void evaluate(Watch watch, Instant now, LocalTime time, List<Effect> effects) {
        StockMonitor m = watch.monitor;
        MarketTick last = m.last();
        Indicators x = indicators(m, now, time);
        watch.indicators = x.values;

        // 구조 무효화: 15분 시가 범위가 끝난 뒤 완료 5분봉 종가가 15분 저가 아래로 마감하면 그날은 다시 풀리지 않아요.
        StockMonitor.Bar five = m.lastFive();
        if (!watch.invalidated && x.or15Final && five != null && m.or15Low() != null
                && !MarketSessionHours.regularTime(five.start).isBefore(LocalTime.of(9, 10))
                && five.close.compareTo(m.or15Low()) < 0)
            watch.invalidated = true;

        Map<String, Check> p = new LinkedHashMap<>();
        p.put("BREAKOUT_5M", x.breakout);
        p.put("ABOVE_VWAP", x.aboveVwap);
        p.put("VOLUME_5M", x.volume);
        p.put("NOT_CHASING_OPEN", x.price == null || x.open == null ? Check.UNAVAILABLE
                : check(pct(x.price, x.open).compareTo(WatchRules.MAX_FROM_OPEN_PCT) <= 0));
        p.put("NOT_CHASING_5M", five == null ? Check.UNAVAILABLE
                : check(pct(five.close, five.open).compareTo(WatchRules.MAX_LAST_5M_GAIN_PCT) < 0));
        p.put("NOT_CHASING_BREAKOUT", x.price == null || m.or15High() == null || !x.or15Final ? Check.UNAVAILABLE
                : check(pct(x.price, m.or15High()).compareTo(WatchRules.MAX_ABOVE_BREAKOUT_PCT) <= 0));
        p.put("STOP_DISTANCE", x.stopDistance);
        p.put("MARKET", marketCheck(now));
        p.put("TIME", x.time);
        p.put("DATA", x.data);
        watch.checksP = p;
        State stateP = state(watch, RULE_P, p, x, last, now, time);
        transition(watch, RULE_P, stateP, failing(p), now, effects);

        if (watch.states.get(RULE_A) == State.UNSUPPORTED) return;
        Map<String, Check> a = new LinkedHashMap<>();
        for (AiCondition condition : m.pick.conditions())
            a.put(condition.label(), conditionCheck(condition, m, x));
        a.put("STOP_AVAILABLE", x.stop == null || x.price == null ? Check.UNAVAILABLE : check(x.price.compareTo(x.stop) > 0));
        a.put("TIME", x.time);
        a.put("DATA", x.data);
        watch.checksA = a;
        State stateA = state(watch, RULE_A, a, x, last, now, time);
        transition(watch, RULE_A, stateA, failing(a), now, effects);
    }

    /**
     * 상태 우선순위: INVALIDATED > EXPIRED(14:30 뒤 미진입) > CAUTION > BUY_CANDIDATE > READY(규칙 P만) > WATCH.
     * CAUTION은 거래정지, 최근 5분 자료 끊김, (규칙 P) 시장 약세예요. 체결이 뜸한 것(60초 넘게 틱 없음)은 매수만 막고 CAUTION은 아니에요.
     */
    private State state(Watch watch, String rule, Map<String, Check> checks, Indicators x, MarketTick last,
            Instant now, LocalTime time) {
        State current = watch.states.get(rule);
        if (current == State.EXPIRED) return State.EXPIRED;
        if (watch.invalidated) return State.INVALIDATED;
        if (time.isAfter(WatchRules.ENTRY_END) && !watch.positions.containsKey(rule)) return State.EXPIRED;
        if ((last != null && last.tradingHalted()) || watch.monitor.recentGap(now)
                || (RULE_P.equals(rule) && checks.get("MARKET") == Check.FAIL)) return State.CAUTION;
        if (checks.values().stream().allMatch(c -> c == Check.PASS)) return State.BUY_CANDIDATE;
        if (RULE_P.equals(rule) && x.price != null && x.or15Final && x.aboveVwap == Check.PASS
                && x.price.compareTo(watch.monitor.or15High().multiply(BigDecimal.ONE.subtract(
                        WatchRules.READY_NEAR_PCT.divide(HUNDRED)))) >= 0) return State.READY;
        return State.WATCH;
    }

    /** 하는 일: 상태가 바뀌었으면 기록하고, BUY_CANDIDATE면서 그 규칙의 진입이 아직 없으면 매수 신호를 내요. */
    private void transition(Watch watch, String rule, State to, List<String> reasons, Instant now, List<Effect> effects) {
        if (watch.states.get(rule) != to)
            effects.add(change(watch, rule, to, reasons, watch.indicators, now));
        if (to == State.BUY_CANDIDATE && !closedOut && !watch.positions.containsKey(rule) && !watch.pending.containsKey(rule)) {
            Indicators x = watch.lastIndicators;
            watch.pending.put(rule, new Pending(now, x.stop));
            effects.add(new EntryRequested(watch.monitor.pick.stockCode(), rule));
        }
    }

    private SignalChanged change(Watch watch, String rule, State to, List<String> reasons, Map<String, Object> indicators,
            Instant evaluatedAt) {
        State from = watch.states.put(rule, to);
        MarketTick last = watch.monitor.last();
        Map<String, Object> values = new LinkedHashMap<>(indicators);
        values.put("checks", RULE_P.equals(rule) ? watch.checksP : watch.checksA);
        return new SignalChanged(watch.monitor.pick.pickId(), watch.monitor.pick.stockCode(), rule, from, to,
                List.copyOf(reasons), values, last == null ? null : last.occurredAt(), watch.monitor.lastReceivedAt(),
                evaluatedAt);
    }

    /** 하는 일: 판단에 쓰는 지표를 한 번에 계산해요. */
    private Indicators indicators(StockMonitor m, Instant now, LocalTime time) {
        Indicators x = new Indicators();
        MarketTick last = m.last();
        x.price = last == null ? null : last.price();
        x.open = last == null ? null : last.openPrice();
        BigDecimal vwap = m.vwap(), atr = m.atr(), rvol = m.rvol();
        StockMonitor.Bar five = m.lastFive();
        x.or15Final = !time.isBefore(WatchRules.OPENING_RANGE_15_END) && m.or15High() != null;
        x.breakout = !x.or15Final ? Check.UNAVAILABLE
                : five == null || MarketSessionHours.regularTime(five.start).isBefore(WatchRules.OPENING_RANGE_15_END)
                ? Check.FAIL : check(five.close.compareTo(m.or15High()) > 0);
        x.aboveVwap = x.price == null || vwap == null ? Check.UNAVAILABLE : check(x.price.compareTo(vwap) > 0);
        BigDecimal fallback = m.profile.previousBarTurnover() == null ? null
                : m.profile.previousBarTurnover().multiply(WatchRules.FALLBACK_TURNOVER_MULTIPLE);
        x.volume = five == null ? Check.UNAVAILABLE : rvol != null ? check(rvol.compareTo(WatchRules.MIN_RVOL) >= 0)
                : fallback != null ? check(five.value.compareTo(fallback) >= 0) : Check.UNAVAILABLE;
        x.fallbackTurnover = fallback;
        if (x.or15Final && vwap != null && atr != null)
            x.stop = m.or15Low().max(vwap.subtract(WatchRules.STOP_VWAP_ATR.multiply(atr))).setScale(4, RoundingMode.HALF_UP);
        if (x.stop == null || x.price == null) x.stopDistance = Check.UNAVAILABLE;
        else {
            BigDecimal distance = x.price.subtract(x.stop);
            x.stopDistance = check(distance.signum() > 0 && distance.compareTo(WatchRules.MIN_STOP_ATR.multiply(atr)) >= 0
                    && distance.multiply(HUNDRED).divide(x.price, 6, RoundingMode.HALF_UP).compareTo(WatchRules.MIN_STOP_PCT) >= 0);
        }
        x.time = check(!time.isBefore(WatchRules.ENTRY_START) && !time.isAfter(WatchRules.ENTRY_END));
        x.data = check(m.dataOk(now));
        x.vwap = vwap; x.rvol = rvol; x.atr = atr;
        Map<String, Object> v = new LinkedHashMap<>();
        put(v, "price", x.price); put(v, "open", x.open); put(v, "vwap", vwap);
        put(v, "or5High", m.or5High()); put(v, "or15High", m.or15High()); put(v, "or15Low", m.or15Low());
        put(v, "previousHigh", m.profile.previousHigh()); put(v, "previousClose", m.profile.previousClose());
        if (five != null) {
            put(v, "last5mStart", five.start); put(v, "last5mClose", five.close);
            put(v, "last5mVolume", five.volume); put(v, "last5mTurnover", five.value.setScale(0, RoundingMode.HALF_UP));
        }
        put(v, "rvol5m", rvol); put(v, "rvolDays", m.profile.days()); put(v, "fallbackTurnover5m", fallback);
        put(v, "atr5m", atr); put(v, "stop", x.stop);
        IndexValue kospi = market.get("KOSPI"), kosdaq = market.get("KOSDAQ");
        if (kospi != null) put(v, "kospiFromOpenPct", kospi.fromOpenPct());
        if (kosdaq != null) put(v, "kosdaqFromOpenPct", kosdaq.fromOpenPct());
        x.values = v;
        watchFor(m).lastIndicators = x;
        return x;
    }

    /** 하는 일: AI 조건 하나를 판단해요. 기준값이 비어 있으면 규칙 P의 같은 기준으로 바꿔 봐요. */
    private Check conditionCheck(AiCondition c, StockMonitor m, Indicators x) {
        StockMonitor.Bar five = m.lastFive();
        Boolean result = switch (c.metric()) {
            case "RVOL_5M" -> x.rvol != null ? c.compare(x.rvol, c.value() != null ? c.value() : WatchRules.MIN_RVOL)
                    : c.value() == null && five != null ? c.compare(five.value, x.fallbackTurnover) : null;
            case "PRICE_VS_VWAP" -> x.price == null || x.vwap == null ? null
                    : percentUnit(c.unit()) ? c.compare(pct(x.price, x.vwap), zero(c.value()))
                    : c.compare(x.price.subtract(x.vwap), zero(c.value()));
            case "CLOSE_5M" -> five == null ? null : c.compare(five.close, reference(c, m, x));
            case "TURNOVER_5M" -> five == null ? null
                    : c.compare(five.value, c.value() != null ? c.value() : x.fallbackTurnover);
            case "PRICE_CHANGE_FROM_OPEN" -> x.price == null || x.open == null ? null
                    : c.compare(pct(x.price, x.open), c.value() != null ? c.value() : WatchRules.MAX_FROM_OPEN_PCT);
            default -> null;
        };
        return result == null ? Check.UNAVAILABLE : check(result);
    }

    private BigDecimal reference(AiCondition c, StockMonitor m, Indicators x) {
        LocalTime five = MarketSessionHours.regularTime(m.lastFive().start);
        if (c.reference() == null && c.value() != null) return c.value();
        String reference = c.reference() == null ? "OPENING_RANGE_15M_HIGH" : c.reference();
        return switch (reference) {
            case "OPENING_RANGE_5M_HIGH" -> five.isBefore(WatchRules.OPENING_RANGE_5_END) ? null : m.or5High();
            case "OPENING_RANGE_15M_HIGH" -> five.isBefore(WatchRules.OPENING_RANGE_15_END) ? null : m.or15High();
            case "PREVIOUS_HIGH" -> m.profile.previousHigh();
            case "PREVIOUS_CLOSE" -> m.profile.previousClose();
            default -> x.vwap;
        };
    }

    private Check marketCheck(Instant now) {
        IndexValue kospi = market.get("KOSPI"), kosdaq = market.get("KOSDAQ");
        if (kospi == null || kosdaq == null || kospi.stale(now) || kosdaq.stale(now)) return Check.UNAVAILABLE;
        return check(kospi.fromOpenPct().compareTo(WatchRules.MARKET_MIN_FROM_OPEN_PCT) >= 0
                && kosdaq.fromOpenPct().compareTo(WatchRules.MARKET_MIN_FROM_OPEN_PCT) >= 0);
    }

    /** 하는 일: 진입 대기를 이번 체결로 가상 진입시켜요. 가격 = 체결가 + 스프레드 절반. */
    private Position open(Watch watch, String rule, Pending pending, ObservedMarketTick event) {
        MarketTick tick = event.tick();
        BigDecimal entry = tick.price().multiply(BigDecimal.ONE.add(pending.halfSpreadPct.divide(HUNDRED, 10, RoundingMode.HALF_UP)))
                .setScale(4, RoundingMode.HALF_UP);
        long quantity = amount.divide(entry, 0, RoundingMode.DOWN).longValue();
        StockMonitor.Bar minute = watch.monitor.lastMinute();
        boolean limited = minute != null && amount.compareTo(minute.value.multiply(WatchRules.CAPACITY_SHARE)) > 0;
        return new Position(new PositionView(watch.monitor.pick.pickId(), watch.monitor.pick.stockCode(), rule, shadow,
                pending.signalAt, tick.occurredAt(), event.receivedAt(), tick.price(), pending.halfSpreadPct,
                pending.spreadSource, entry, quantity, limited, pending.stop, null, null, tick.price(), tick.price(),
                tick.openPrice(), false));
    }

    /** 하는 일: 화면용으로 지금 모든 종목의 상태·지표·포지션을 복사해 돌려줘요. */
    public synchronized List<StockView> view() {
        List<StockView> views = new ArrayList<>();
        for (Watch watch : watches.values()) {
            var pick = watch.monitor.pick;
            views.add(new StockView(pick.pickId(), pick.stockCode(), pick.stockName(), pick.priority(), pick.topPriority(),
                    watch.subscribed, watch.states.get(RULE_P), watch.states.get(RULE_A), Map.copyOf(watch.indicators),
                    Collections.unmodifiableMap(new LinkedHashMap<>(watch.checksP)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(watch.checksA)), watch.unsupportedA,
                    watch.positions.values().stream().map(Position::view).toList()));
        }
        return views;
    }

    private Watch watchFor(StockMonitor m) { return watches.get(m.pick.stockCode()); }

    private static List<String> failing(Map<String, Check> checks) {
        return checks.entrySet().stream().filter(e -> e.getValue() != Check.PASS)
                .map(e -> e.getKey() + ":" + e.getValue()).toList();
    }

    private static Check check(boolean pass) { return pass ? Check.PASS : Check.FAIL; }

    private static boolean percentUnit(String unit) { return "PERCENT".equalsIgnoreCase(unit) || "%".equals(unit) || "PCT".equalsIgnoreCase(unit); }

    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    /** (a ÷ b − 1) × 100 */
    static BigDecimal pct(BigDecimal a, BigDecimal b) {
        return a.divide(b, 10, RoundingMode.HALF_UP).subtract(BigDecimal.ONE).multiply(HUNDRED).setScale(4, RoundingMode.HALF_UP);
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : value);
    }

    /** 비용을 뺀 수익률(%) = 매도 대금(매도 비용 차감) ÷ 매수 대금(매수 비용 포함) − 1. */
    static BigDecimal net(BigDecimal entry, BigDecimal exit, TradingCostProperties costs) {
        BigDecimal buy = entry.multiply(BigDecimal.ONE.add(costs.buyFeePercent().add(costs.buySlippagePercent())
                .divide(HUNDRED, 10, RoundingMode.HALF_UP)));
        BigDecimal sell = exit.multiply(BigDecimal.ONE.subtract(costs.sellFeePercent().add(costs.sellTaxPercent())
                .add(costs.sellSlippagePercent()).divide(HUNDRED, 10, RoundingMode.HALF_UP)));
        return pct(sell, buy);
    }

    /** 종목 하나의 감시 상태 묶음이에요. */
    private static final class Watch {
        final StockMonitor monitor;
        final boolean subscribed;
        final List<String> unsupportedA;
        final Map<String, State> states = new HashMap<>();
        final Map<String, Pending> pending = new LinkedHashMap<>();
        final Map<String, Position> positions = new LinkedHashMap<>();
        boolean invalidated;
        Map<String, Object> indicators = Map.of();
        Map<String, Check> checksP = Map.of(), checksA = Map.of();
        Indicators lastIndicators;

        Watch(StockMonitor monitor, boolean subscribed, List<String> unsupportedA) {
            this.monitor = monitor; this.subscribed = subscribed; this.unsupportedA = List.copyOf(unsupportedA);
        }
    }

    /** 한 번 판단할 때의 지표 값이에요. */
    private static final class Indicators {
        BigDecimal price, open, vwap, rvol, atr, stop, fallbackTurnover;
        boolean or15Final;
        Check breakout, aboveVwap, volume, stopDistance, time, data;
        Map<String, Object> values;
    }

    /** 매수 신호 뒤 진입 대기예요. armedAt이 null이면 아직 호가를 조회하는 중이에요. */
    private static final class Pending {
        final Instant signalAt;
        final BigDecimal stop;
        BigDecimal halfSpreadPct;
        String spreadSource;
        Instant armedAt;

        Pending(Instant signalAt, BigDecimal stop) { this.signalAt = signalAt; this.stop = stop; }
    }

    /** 지수 하나의 마지막 값과 시가예요. */
    private record IndexValue(BigDecimal value, BigDecimal open, Instant at) {
        BigDecimal fromOpenPct() { return pct(value, open); }
        boolean stale(Instant now) { return Duration.between(at, now).compareTo(WatchRules.MARKET_STALE) > 0; }
    }

    /** 가상 포지션 하나예요. E1(구조 손절+마감)과 E2(+3%/−2%)를 한 진입에서 함께 따라가요. */
    private static final class Position {
        final PositionView base;
        Exit e1, e2;
        BigDecimal max, min;
        boolean trackingGap;

        Position(PositionView view) {
            base = view; e1 = view.e1(); e2 = view.e2(); max = view.maxPrice(); min = view.minPrice();
            trackingGap = view.trackingGap();
        }

        /** 하는 일: 체결 하나로 최고·최저가와 두 청산을 확인해요. 돌려주는 것: 청산이 새로 났으면 true. */
        boolean track(MarketTick tick, TradingCostProperties costs) {
            if (e1 != null && e2 != null) return false;
            BigDecimal price = tick.price();
            max = max.max(price); min = min.min(price);
            boolean changed = false;
            if (e1 == null && price.compareTo(base.stopPrice()) <= 0) { e1 = exit(price, tick.occurredAt(), "STOP", costs); changed = true; }
            if (e2 == null) {
                BigDecimal move = pct(price, base.entryPrice());
                if (move.compareTo(WatchRules.E2_TAKE_PCT) >= 0) { e2 = exit(price, tick.occurredAt(), "TAKE", costs); changed = true; }
                else if (move.compareTo(WatchRules.E2_STOP_PCT) <= 0) { e2 = exit(price, tick.occurredAt(), "STOP", costs); changed = true; }
            }
            return changed;
        }

        /** 하는 일: 15:19에 남은 청산을 마지막 체결 가격으로 정리해요. */
        boolean closeOut(MarketTick last, TradingCostProperties costs) {
            boolean changed = false;
            if (e1 == null) { e1 = exit(last.price(), last.occurredAt(), "CLOSE", costs); changed = true; }
            if (e2 == null) { e2 = exit(last.price(), last.occurredAt(), "CLOSE", costs); changed = true; }
            return changed;
        }

        /** 청산 가격 = 체결가 − 스프레드 절반. */
        private Exit exit(BigDecimal price, Instant at, String reason, TradingCostProperties costs) {
            BigDecimal exit = price.multiply(BigDecimal.ONE.subtract(base.halfSpreadPct().divide(HUNDRED, 10, RoundingMode.HALF_UP)))
                    .setScale(4, RoundingMode.HALF_UP);
            return new Exit(exit, at, reason, net(base.entryPrice(), exit, costs));
        }

        PositionView view() {
            return new PositionView(base.pickId(), base.stockCode(), base.rule(), base.shadow(), base.signalAt(),
                    base.entrySourceAt(), base.entryReceivedAt(), base.entryTickPrice(), base.halfSpreadPct(),
                    base.spreadSource(), base.entryPrice(), base.quantity(), base.capacityLimited(), base.stopPrice(),
                    e1, e2, max, min, base.dayOpenPrice(), trackingGap);
        }
    }
}
