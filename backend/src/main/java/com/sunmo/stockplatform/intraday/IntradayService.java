package com.sunmo.stockplatform.intraday;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.intraday.IntradayModel.*;
import com.sunmo.stockplatform.market.application.*;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.PageRequest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class IntradayService {
    private final IntradayProperties policy;
    private final IntradayInputRepository inputs;
    private final IntradayRecommendationRepository recommendations;
    private final StockRepository stocks;
    private final ClosingTradingCalendar calendar;
    private final ObjectMapper mapper;
    private final MarketEventGateway events;
    private final TransactionTemplate transaction;
    private final ArrayBlockingQueue<ObservedMarketTick> queue;
    private final AtomicLong dropped = new AtomicLong();
    private long acknowledgedDrops;
    private boolean recoveryBoundary = true;
    private IntradayEngine engine;
    private LocalDate date;
    private String error;

    public IntradayService(IntradayProperties policy, IntradayInputRepository inputs,
                           IntradayRecommendationRepository recommendations, StockRepository stocks,
                           ClosingTradingCalendar calendar, ObjectMapper mapper, MarketEventGateway events,
                           PlatformTransactionManager transactions) {
        this.policy = policy; this.inputs = inputs; this.recommendations = recommendations; this.stocks = stocks;
        this.calendar = calendar; this.mapper = mapper; this.events = events;
        this.queue = new ArrayBlockingQueue<>(policy.queueCapacity());
        this.transaction = new TransactionTemplate(transactions);
    }

    /** Closing transaction commits first. Intraday IO and failures cannot roll it back or consume KIS slots. */
    @TransactionalEventListener
    public void receive(ObservedMarketTick event) {
        if (policy.enabled() && !queue.offer(event)) dropped.incrementAndGet();
    }

    @Scheduled(fixedDelayString = "${intraday.flush-interval:1s}", scheduler = "intradayScheduler")
    public synchronized void flush() {
        if (!policy.enabled()) return;
        List<ObservedMarketTick> batch = new ArrayList<>();
        try {
            if (engine == null || !calendar.today().equals(date)) recover();
            queue.drainTo(batch, policy.batchSize());
            long dropCount = dropped.get();
            boolean lost = recoveryBoundary || dropCount != acknowledgedDrops;
            List<Signal> created = new ArrayList<>();
            transaction.executeWithoutResult(ignored -> {
                Map<String, Optional<Stock>> masters = new HashMap<>();
                for (ObservedMarketTick event : batch) {
                    if (!event.tick().businessDate().equals(date)) continue;
                    var stock = masters.computeIfAbsent(event.tick().stockCode(), stocks::findByStockCodeAndActiveTrue).orElse(null);
                    boolean eligible = stock != null && !stock.isManaged() && !stock.isTradingHalted()
                            && stock.getMarketType() == com.sunmo.stockplatform.stock.domain.MarketType.STOCK;
                    Input input = new Input(event, calendar.now(), stock == null ? event.tick().stockCode() : stock.getStockName(),
                            eligible, lost, policy);
                    inputs.save(new IntradayInputEntity(input, json(input)));
                    Signal signal = engine.accept(input);
                    if (signal != null) created.add(signal);
                }
                engine.advance(calendar.now());
                persist(engine);
            });
            acknowledgedDrops = dropCount; error = null;
            if (!batch.isEmpty()) recoveryBoundary = false;
            for (Signal signal : created) events.publish("intraday.entry-ready", Map.of("id", signal.id().toString(),
                    "stockCode", signal.stockCode(), "signal", signal));
        } catch (RuntimeException failure) {
            // Rolled-back in-memory state must never leak into the next committed outcome.
            engine = null; recoveryBoundary = true; dropped.addAndGet(batch.size()); error = "INTRADAY_PROCESSING_FAILED";
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Intraday batch failed ({})", failure.getClass().getSimpleName());
        }
    }

    private void recover() {
        LocalDate today = calendar.today();
        transaction.executeWithoutResult(ignored -> {
            // Finalize previous observed day without substituting a current quote for its missing close.
            for (var entity : recommendations.findByTrackingCompleteFalse()) {
                Result r = result(entity); IntradayTracker.advance(r, calendar.now());
                entity.updateOutcome(json(r.outcome()), r.outcome().trackingComplete);
            }
        });
        engine = replayEngine(today, null);
        // Signals/outcomes are the authoritative paper record, including expiry during periods with no ticks.
        for (var row : recommendations.findBySessionDateOrderByRecommendedAtAsc(today)) engine.restore(result(row));
        date = today;
    }

    private void persist(IntradayEngine target) {
        Map<UUID, IntradayRecommendationEntity> existing = new HashMap<>();
        recommendations.findAllById(target.results().stream().map(r -> r.signal().id()).toList())
                .forEach(row -> existing.put(row.getId(), row));
        for (Result result : target.results()) {
            String outcome = json(result.outcome());
            var row = existing.get(result.signal().id());
            if (row == null) row = new IntradayRecommendationEntity(result.signal(), json(result.signal()), outcome);
            else if (row.getOutcome().equals(outcome)) continue;
            row.updateOutcome(outcome, result.outcome().trackingComplete); recommendations.save(row);
        }
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", policy.enabled()); status.put("costsConfigured", policy.costs().configured());
        status.put("version", policy.version()); status.put("queued", queue.size()); status.put("dropped", dropped.get());
        status.put("error", error); status.put("mode", "RESEARCH_PAPER");
        status.put("candidates", engine == null ? List.of() : engine.candidates());
        status.put("unavailable", IntradayFeatures.UNAVAILABLE);
        return status;
    }
    @Scheduled(fixedDelayString = "${intraday.maintenance-interval:60s}", scheduler = "intradayScheduler")
    public synchronized void expireWhileDisabled() {
        if (policy.enabled()) return;
        transaction.executeWithoutResult(ignored -> {
            for (var row : recommendations.findByTrackingCompleteFalse()) {
                Result r = result(row); IntradayTracker.advance(r, calendar.now());
                row.updateOutcome(json(r.outcome()), r.outcome().trackingComplete);
            }
        });
    }
    public List<Result> list(LocalDate date) {
        return recommendations.findBySessionDateOrderByRecommendedAtAsc(date).stream().map(this::result)
                .sorted(Comparator.comparingDouble((Result r) -> r.signal().score()).reversed()).toList();
    }
    public Map<String, Object> replay(LocalDate date, IntradayProperties override) {
        if (date.isAfter(calendar.today())) throw new IllegalArgumentException("Future session is not replayable");
        IntradayEngine replay = replayEngine(date, override);
        Instant end = date.atTime(15, 31).atZone(IntradayEngine.ZONE).toInstant();
        replay.advance(end.isBefore(calendar.now()) ? end : calendar.now());
        return Map.of("mode", override == null ? "RECEIPT_REPLAY" : "COUNTERFACTUAL_REPLAY", "inputCount", inputs.countBySessionDate(date),
                "results", replay.results(), "analytics", IntradayAnalytics.analyze(replay.results()),
                "limitations", List.of("CAPTURED_SUBSCRIBED_UNIVERSE_ONLY", "NO_HISTORICAL_TICK_BACKFILL",
                        "NO_OOS_PROFITABILITY_CLAIM", "DRAWDOWN_IS_EQUAL_NOTIONAL_RETURN_POINTS_NOT_ACCOUNT_NAV"));
    }
    private IntradayEngine replayEngine(LocalDate day, IntradayProperties override) {
        IntradayEngine target = new IntradayEngine(calendar);
        long after = 0;
        while (true) {
            var page = inputs.findBySessionDateAndIdGreaterThanOrderById(day, after, PageRequest.of(0, 2000));
            if (page.isEmpty()) return target;
            for (var row : page) {
                Input input = read(row.getPayload(), Input.class);
                if (override != null) input = new Input(input.observation(), input.evaluatedAt(), input.stockName(),
                        input.eligible(), input.dataLoss(), override);
                target.accept(input); after = row.getId();
            }
        }
    }
    private Result result(IntradayRecommendationEntity row) {
        return new Result(read(row.getSnapshot(), Signal.class), read(row.getOutcome(), Outcome.class));
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Intraday serialization failed", e); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Intraday history is not readable", e); }
    }
}
