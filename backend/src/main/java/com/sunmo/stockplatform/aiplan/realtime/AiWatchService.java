/*
 * AI 장전 계획 2단계(실시간 감시·가상 매수)를 Spring에 연결하는 파일이에요.
 * 08:50부터 그날 집계 대상 계획을 읽어 종목을 AI_PLAN 출처로 구독하고(우선순위 순, 정밀 후보 자리를 먼저 양보받음),
 * 틱을 AiWatchEngine에 넘기고, 1초마다 분 판단·저장·알림을 하고, 1분마다 지수를 조회해요. 15:20에 구독을 풀어요.
 * 실제 주문은 없어요. 판단 규칙은 AiWatchEngine·WatchRules, 저장은 AiWatchStore에 있어요.
 */
package com.sunmo.stockplatform.aiplan.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.aiplan.config.AiWatchProperties;
import com.sunmo.stockplatform.aiplan.realtime.AiWatchEngine.Effect;
import com.sunmo.stockplatform.aiplan.realtime.AiWatchEngine.EntryRequested;
import com.sunmo.stockplatform.aiplan.realtime.AiWatchEngine.PositionChanged;
import com.sunmo.stockplatform.aiplan.realtime.AiWatchEngine.PositionView;
import com.sunmo.stockplatform.aiplan.realtime.AiWatchEngine.SignalChanged;
import com.sunmo.stockplatform.aiplan.realtime.AiWatchEngine.StockView;
import com.sunmo.stockplatform.aiplan.realtime.WatchRules.State;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.closing.application.MarketSessionHours;
import com.sunmo.stockplatform.closing.config.TradingCostProperties;
import com.sunmo.stockplatform.market.application.MarketEventGateway;
import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.marketwide.application.PrecisionSubscriptionAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** AI 실시간 감시 운영자예요. 하루에 엔진 하나를 만들고, 구독·저장·알림을 맡아요. */
@Service
public class AiWatchService {
    private static final Logger log = LoggerFactory.getLogger(AiWatchService.class);
    static final LocalTime LOAD_FROM = LocalTime.of(8, 50);
    // 2단계 판정(사전 등록): 규칙 P·청산 E1 가상 거래가 40건 이상이고 15거래일 이상 쌓인 첫 시점의 표본으로 한 번 판정해요.
    static final int GATE_TRADES = 40;
    static final int GATE_DAYS = 15;

    private final AiWatchStore store;
    private final ObjectMapper json;
    private final ClosingTradingCalendar calendar;
    private final TradingCostProperties costs;
    private final AiWatchProperties properties;
    private final RealtimeSubscriptionRegistry registry;
    private final PrecisionSubscriptionAllocator precision;
    private final AiMarketClient market;
    private final MarketEventGateway events;
    private final Queue<Effect> outbox = new ConcurrentLinkedQueue<>();
    private final Set<String> subscribed = new LinkedHashSet<>();
    private volatile AiWatchEngine engine;
    private long planId;
    private boolean finished;

    public AiWatchService(AiWatchStore store, ObjectMapper json, ClosingTradingCalendar calendar, TradingCostProperties costs,
            AiWatchProperties properties, RealtimeSubscriptionRegistry registry, PrecisionSubscriptionAllocator precision,
            AiMarketClient market, MarketEventGateway events) {
        this.store = store;
        this.json = json;
        this.calendar = calendar;
        this.costs = costs;
        this.properties = properties;
        this.registry = registry;
        this.precision = precision;
        this.market = market;
        this.events = events;
    }

    /** 하는 일: (30초마다) 그날 계획을 확인해 감시를 시작하거나, 장 시작 전 새 판이 오면 바꾸거나, 날이 바뀌면 정리해요. */
    @Scheduled(fixedDelayString = "${ai-plan.watch.sync-interval:30s}", initialDelayString = "20s", scheduler = "aiWatchScheduler")
    public void sync() { sync(calendar.now()); }

    synchronized void sync(Instant now) {
        LocalDate today = now.atZone(ClosingTradingCalendar.ZONE).toLocalDate();
        if (!properties.enabled() || (engine != null && !engine.date().equals(today))) stop();
        if (!properties.enabled() || !calendar.isTradingDay(today)) return;
        LocalTime time = MarketSessionHours.regularTime(now);
        if (time.isBefore(LOAD_FROM) || !time.isBefore(WatchRules.SESSION_END)) return;
        AiWatchStore.PlanRow plan = store.plan(today).orElse(null);
        if (plan == null) return;
        // 장 시작 뒤에는 계획이 바뀌지 않아요(장 시작 뒤 판은 집계 대상이 아님). 재시작이면 engine이 없어서 다시 만들어요.
        if (engine != null && (plan.id() == planId || !time.isBefore(MarketSessionHours.REGULAR_OPEN))) return;
        load(today, plan, now);
    }

    /** 하는 일: 계획 종목을 우선순위 순으로 구독하고 엔진을 만들어요. 재시작이면 DB의 상태·포지션을 되살려요. */
    private void load(LocalDate today, AiWatchStore.PlanRow plan, Instant now) {
        List<AiWatchStore.PickRow> picks = store.picks(plan.id());
        Map<String, JsonNode> raw = rawPicks(plan.rawJson());
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = today; days.size() < WatchRules.RVOL_DAYS; ) days.add(day = calendar.previousTradingDay(day));
        AiWatchEngine next = new AiWatchEngine(today, plan.tradeDecision(), properties.positionAmount(), costs);
        Set<String> wanted = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        for (AiWatchStore.PickRow pick : picks) {
            wanted.add(pick.stockCode());
            boolean ok = subscribe(pick.stockCode());
            if (!ok) missing.add(pick.stockCode());
            AiCondition.Parsed conditions = AiCondition.parse(raw.get(pick.stockCode()));
            StockMonitor.Profile profile;
            try {
                profile = store.profile(pick.stockId(), days, today);
            } catch (RuntimeException failure) {
                log.warn("AI watch profile unavailable for {} ({})", pick.stockCode(), failure.getClass().getSimpleName());
                profile = StockMonitor.Profile.empty();
            }
            next.add(new StockMonitor.Pick(pick.id(), pick.stockCode(), pick.stockName(), pick.priority(), pick.topPriority(),
                    conditions.conditions(), conditions.supported()), profile, ok, conditions.unsupported());
        }
        for (String code : new ArrayList<>(subscribed))
            if (!wanted.contains(code)) { registry.remove(code, RealtimeSubscriptionRegistry.Source.AI_PLAN); subscribed.remove(code); }
        store.lastStates(today).forEach((key, state) -> {
            String[] parts = key.split("\\|");
            next.restoreState(parts[0], parts[1], state);
        });
        store.positions(today).forEach(next::restore);
        store.session(today, plan, picks.size(), picks.size() - missing.size(), missing, properties.positionAmount(), now);
        engine = next;
        planId = plan.id();
        finished = false;
        log.info("AI watch loaded plan {} for {}: {} picks, {} subscribed, missing {}", plan.id(), today, picks.size(),
                picks.size() - missing.size(), missing);
    }

    /**
     * 하는 일: 종목 하나를 AI_PLAN 출처로 구독해요. 이미 다른 출처로 구독 중이거나 빈자리가 있으면 바로 넣고,
     * 꽉 찼으면 정밀 후보 중 가장 약한 자리를 양보받아요. 돌려주는 것: 구독했으면 true.
     */
    private boolean subscribe(String code) {
        if (subscribed.contains(code)) return true;
        try {
            if (registry.entries().containsKey(code) || registry.remaining() > 0) {
                registry.add(code, RealtimeSubscriptionRegistry.Source.AI_PLAN);
                subscribed.add(code);
                return true;
            }
        } catch (IllegalStateException full) {
            // 확인과 등록 사이에 자리가 찼어요. 정밀 자리 양보로 넘어가요.
        }
        if (precision.yieldSlotTo(code, RealtimeSubscriptionRegistry.Source.AI_PLAN)) {
            subscribed.add(code);
            return true;
        }
        return false;
    }

    private Map<String, JsonNode> rawPicks(String raw) {
        Map<String, JsonNode> picks = new HashMap<>();
        try {
            JsonNode root = json.readTree(com.sunmo.stockplatform.aiplan.application.AiPlanValidator.stripFence(raw));
            root.path("picks").forEach(node -> picks.put(node.path("stockCode").asText(), node));
        } catch (JsonProcessingException failure) {
            log.warn("AI watch could not read the plan JSON ({})", failure.getOriginalMessage());
        }
        return picks;
    }

    private void stop() {
        for (String code : subscribed) registry.remove(code, RealtimeSubscriptionRegistry.Source.AI_PLAN);
        subscribed.clear();
        engine = null;
        planId = 0;
    }

    /** 하는 일: 감시 종목의 틱을 엔진에 넘겨요. 결과 저장은 1초 작업이 해요(틱 스레드에서 DB를 쓰지 않음). */
    @TransactionalEventListener(fallbackExecution = true)
    public void receive(ObservedMarketTick event) {
        AiWatchEngine current = engine;
        if (current == null) return;
        List<Effect> effects = current.accept(event);
        if (!effects.isEmpty()) outbox.addAll(effects);
    }

    /** 하는 일: (1초마다) 분 판단을 하고, 결과를 저장·알리고, 매수 신호면 호가를 조회해 진입 대기로 바꿔요. 15:20에 구독을 풀어요. */
    @Scheduled(fixedDelay = 1000, initialDelay = 30000, scheduler = "aiWatchScheduler")
    public void evaluate() { evaluate(calendar.now()); }

    void evaluate(Instant now) {
        AiWatchEngine current = engine;
        if (current == null) return;
        List<Effect> effects = new ArrayList<>(current.evaluate(now));
        for (Effect effect; (effect = outbox.poll()) != null; ) effects.add(effect);
        for (Effect effect : effects) {
            try {
                handle(current, effect);
            } catch (RuntimeException failure) {
                log.warn("AI watch effect not handled {} ({})", effect.getClass().getSimpleName(), failure.getClass().getSimpleName());
            }
        }
        if (!MarketSessionHours.regularTime(now).isBefore(WatchRules.SESSION_END)) finish(current, now);
    }

    private synchronized void finish(AiWatchEngine current, Instant now) {
        if (finished || current != engine) return;
        finished = true;
        for (String code : subscribed) registry.remove(code, RealtimeSubscriptionRegistry.Source.AI_PLAN);
        subscribed.clear();
        store.endSession(current.date(), now);
        log.info("AI watch finished for {}", current.date());
    }

    private void handle(AiWatchEngine current, Effect effect) {
        switch (effect) {
            case SignalChanged change -> {
                store.event(current.date(), change, write(change.indicators()));
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("tradeDate", current.date().toString());
                payload.put("stockCode", change.stockCode());
                payload.put("rule", change.rule());
                payload.put("from", change.from() == null ? null : change.from().name());
                payload.put("to", change.to().name());
                payload.put("reasons", change.reasons());
                payload.put("evaluatedAt", change.evaluatedAt().toString());
                events.publish("ai.signal.changed", payload);
            }
            case EntryRequested request -> {
                Optional<BigDecimal> spread = market.halfSpreadPct(request.stockCode());
                current.arm(request.stockCode(), request.rule(), spread.orElse(WatchRules.FALLBACK_HALF_SPREAD_PCT),
                        spread.isPresent() ? "ORDERBOOK" : "FALLBACK", calendar.now());
            }
            case PositionChanged change -> {
                store.position(current.date(), change.position(), costText());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("tradeDate", current.date().toString());
                payload.put("stockCode", change.position().stockCode());
                payload.put("rule", change.position().rule());
                payload.put("opened", change.opened());
                payload.put("shadow", change.position().shadow());
                payload.put("entryPrice", change.position().entryPrice());
                payload.put("e1", change.position().e1());
                payload.put("e2", change.position().e2());
                events.publish("ai.position.changed", payload);
            }
        }
    }

    /** 하는 일: (1분마다) 감시 중이고 장중이면 KOSPI·KOSDAQ 지수를 조회해 엔진에 넘겨요(시장 필터). */
    @Scheduled(fixedDelay = 60000, initialDelay = 30000, scheduler = "aiWatchMarketScheduler")
    public void indices() {
        AiWatchEngine current = engine;
        Instant now = calendar.now();
        LocalTime time = MarketSessionHours.regularTime(now);
        if (current == null || finished || time.isBefore(MarketSessionHours.REGULAR_OPEN) || !time.isBefore(WatchRules.SESSION_END))
            return;
        market.index("0001").ifPresent(q -> current.market("KOSPI", q.value(), q.open(), calendar.now()));
        market.index("1001").ifPresent(q -> current.market("KOSDAQ", q.value(), q.open(), calendar.now()));
    }

    /** 화면용 하루 감시 모습이에요. live: 지금 장중 감시 중인지. */
    public record WatchView(LocalDate date, boolean live, AiWatchStore.SessionRow session, List<StockView> stocks,
            List<PositionView> positions, List<AiWatchStore.EventRow> events, String ruleVersion, BigDecimal positionAmount) { }

    /** 하는 일: 하루 감시 모습을 돌려줘요. 오늘 장중이면 메모리의 현재 상태, 지난 날이면 DB 기록으로 만들어요. */
    public WatchView view(LocalDate date) {
        AiWatchEngine current = engine;
        AiWatchStore.SessionRow session = store.session(date).orElse(null);
        List<PositionView> positions = store.positions(date);
        List<StockView> stocks;
        if (current != null && current.date().equals(date)) stocks = current.view();
        else stocks = session == null ? List.of() : stored(date, session, positions);
        return new WatchView(date, current != null && current.date().equals(date) && !finished, session, stocks, positions,
                store.events(date), WatchRules.RULE_VERSION, properties.positionAmount());
    }

    private List<StockView> stored(LocalDate date, AiWatchStore.SessionRow session, List<PositionView> positions) {
        Map<String, State> states = store.lastStates(date);
        Set<String> missing = session.unsubscribedCodes() == null ? Set.of() : Set.of(session.unsubscribedCodes().split(","));
        List<StockView> views = new ArrayList<>();
        for (AiWatchStore.PickRow pick : store.picks(session.planId())) {
            boolean ok = !missing.contains(pick.stockCode());
            State fallback = ok ? State.WATCH : State.NOT_SUBSCRIBED;
            views.add(new StockView(pick.id(), pick.stockCode(), pick.stockName(), pick.priority(), pick.topPriority(), ok,
                    states.getOrDefault(pick.stockCode() + "|P", fallback), states.getOrDefault(pick.stockCode() + "|A", fallback),
                    Map.of(), Map.of(), Map.of(), List.of(),
                    positions.stream().filter(p -> p.stockCode().equals(pick.stockCode())).toList()));
        }
        return views;
    }

    /** 규칙·청산 조합 하나의 집계예요. vsOpen: 같은 종목을 시가에 사서 종가에 판 경우(비용 뺀)보다 나은 정도(%p). */
    public record Group(String rule, String exit, String scope, int trades, int days, BigDecimal meanNetPct,
            BigDecimal winRate, int paired, BigDecimal meanVsOpenPct) { }

    /** 2단계 판정이에요. verdict: PENDING(표본 부족)·PASS·FAIL. gate는 판정에 쓰는 규칙 P·E1 표본이에요. */
    public record Summary(String verdict, int minTrades, int minDays, Group gate, List<Group> groups) { }

    /**
     * 하는 일: 가상 거래를 규칙(P/A)·청산(E1/E2)·구분(전체/실제/shadow)별로 집계하고, 규칙 P·E1으로 판정해요.
     * 판정 표본은 날짜 순으로 쌓아 40건 이상·15거래일 이상이 처음 된 날까지예요. 그 뒤 거래는 판정을 바꾸지 않아요.
     */
    public Summary summary() {
        List<AiWatchStore.TradeRow> trades = store.trades();
        List<Group> groups = new ArrayList<>();
        for (String rule : List.of(AiWatchEngine.RULE_P, AiWatchEngine.RULE_A))
            for (String exit : List.of("E1", "E2"))
                for (String scope : List.of("ALL", "LIVE", "SHADOW"))
                    groups.add(group(rule, exit, scope, trades.stream().filter(t -> t.rule().equals(rule))
                            .filter(t -> scope.equals("ALL") || (scope.equals("SHADOW") == t.shadow())).toList()));
        List<AiWatchStore.TradeRow> sample = new ArrayList<>();
        Set<LocalDate> days = new TreeSet<>();
        boolean reached = false;
        LocalDate cutoff = null;
        for (AiWatchStore.TradeRow trade : trades.stream().filter(t -> t.rule().equals(AiWatchEngine.RULE_P)).toList()) {
            if (cutoff != null && trade.tradeDate().isAfter(cutoff)) break;
            sample.add(trade);
            days.add(trade.tradeDate());
            if (!reached && sample.size() >= GATE_TRADES && days.size() >= GATE_DAYS) { reached = true; cutoff = trade.tradeDate(); }
        }
        Group gate = group(AiWatchEngine.RULE_P, "E1", "GATE", sample);
        String verdict = !reached ? "PENDING"
                : gate.meanNetPct() != null && gate.meanNetPct().signum() > 0
                        && gate.meanVsOpenPct() != null && gate.meanVsOpenPct().signum() > 0 ? "PASS" : "FAIL";
        return new Summary(verdict, GATE_TRADES, GATE_DAYS, gate, groups);
    }

    private static Group group(String rule, String exit, String scope, List<AiWatchStore.TradeRow> rows) {
        List<BigDecimal> nets = rows.stream().map(t -> "E1".equals(exit) ? t.e1NetPct() : t.e2NetPct()).toList();
        List<BigDecimal> vsOpen = rows.stream().filter(t -> t.openCloseNetPct() != null)
                .map(t -> ("E1".equals(exit) ? t.e1NetPct() : t.e2NetPct()).subtract(t.openCloseNetPct())).toList();
        long wins = nets.stream().filter(n -> n.signum() > 0).count();
        return new Group(rule, exit, scope, rows.size(), (int) rows.stream().map(AiWatchStore.TradeRow::tradeDate).distinct().count(),
                mean(nets), nets.isEmpty() ? null : BigDecimal.valueOf(wins * 100.0 / nets.size()).setScale(1, RoundingMode.HALF_UP),
                vsOpen.size(), mean(vsOpen));
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        if (values.isEmpty()) return null;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (JsonProcessingException failure) { return null; }
    }

    private String costText() {
        return "{\"buyFeePercent\":\"" + costs.buyFeePercent().toPlainString() + "\",\"sellFeePercent\":\""
                + costs.sellFeePercent().toPlainString() + "\",\"sellTaxPercent\":\"" + costs.sellTaxPercent().toPlainString()
                + "\",\"buySlippagePercent\":\"" + costs.buySlippagePercent().toPlainString()
                + "\",\"sellSlippagePercent\":\"" + costs.sellSlippagePercent().toPlainString() + "\"}";
    }
}
