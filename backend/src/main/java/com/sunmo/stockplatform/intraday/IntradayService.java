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
    private final IntradayInputWriter writer;
    private final IntradayRecommendationRepository recommendations;
    private final StockRepository stocks;
    private final ClosingTradingCalendar calendar;
    private final ObjectMapper mapper;
    private final MarketEventGateway events;
    private final TransactionTemplate transaction;
    private final ArrayBlockingQueue<ObservedMarketTick> queue;
    private final AtomicLong dropped = new AtomicLong();
    private long acknowledgedDrops;
    private final Set<String> recoveryPending = new HashSet<>();
    private final AtomicLong sourceLossEvents = new AtomicLong();
    private final IntradayCheckpointStore checkpoints;
    private IntradayEngine engine;
    private LocalDate date;
    private String error;
    // Only committed JSON belongs here; failed transactions invalidate this together with the engine.
    private final Map<UUID, String> committedOutcomes = new HashMap<>();

    public IntradayService(IntradayProperties policy, IntradayInputRepository inputs,
                           IntradayRecommendationRepository recommendations, StockRepository stocks,
                           ClosingTradingCalendar calendar, ObjectMapper mapper, MarketEventGateway events,
                           PlatformTransactionManager transactions, IntradayInputWriter writer, IntradayCheckpointStore checkpoints) {
        this.checkpoints = checkpoints;
        this.writer = writer;
        this.policy = policy; this.inputs = inputs; this.recommendations = recommendations; this.stocks = stocks;
        this.calendar = calendar; this.mapper = mapper; this.events = events;
        this.queue = new ArrayBlockingQueue<>(policy.queueCapacity());
        this.transaction = new TransactionTemplate(transactions);
    }

    /** Candle work commits before publication; transaction-free ticks can be observed immediately. */
    @TransactionalEventListener(fallbackExecution = true)
    public void receive(ObservedMarketTick event) {
        if (policy.enabled() && event.dataLoss()) sourceLossEvents.incrementAndGet();
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
            boolean lost = dropCount != acknowledgedDrops;
            if (lost) recoveryPending.addAll(engine.observedSymbols());
            List<Signal> created = new ArrayList<>();
            Map<String, Optional<Stock>> masters = new HashMap<>();
            List<Input> recorded = new ArrayList<>();
            for (ObservedMarketTick event : batch) {
                if (!event.tick().businessDate().equals(date)) continue;
                var stock = masters.computeIfAbsent(event.tick().stockCode(), stocks::findByStockCodeAndActiveTrue).orElse(null);
                boolean eligible = stock != null && !stock.isManaged() && !stock.isTradingHalted()
                        && stock.getMarketType() == com.sunmo.stockplatform.stock.domain.MarketType.STOCK;
                boolean boundary = recoveryPending.remove(event.tick().stockCode()) || event.dataLoss();
                Input input = new Input(event, calendar.now(), stock == null ? event.tick().stockCode() : stock.getStockName(),
                        eligible, boundary || lost, policy);
                // Overflow is not a reconnect: never waive its loss marker. Keep paper tracking conservative.
                if (boundary && !lost && engine.canContinue(input)) input = new Input(event, input.evaluatedAt(),
                        input.stockName(), eligible, true, policy, true);
                recorded.add(input);
                Signal signal = engine.accept(input);
                if (signal != null) created.add(signal);
            }
            // Expiry still advances without ticks. Only actual changes require a database transaction.
            engine.advance(calendar.now());
            Map<UUID, String> changed = changedOutcomes(engine);
            if (!recorded.isEmpty() || !changed.isEmpty()) {
                transaction.executeWithoutResult(ignored -> {
                    writer.save(recorded);
                    persist(engine, changed);
                });
                committedOutcomes.putAll(changed);
            }
            acknowledgedDrops = dropCount; error = null;
            if (!recorded.isEmpty() && checkpoints.due(calendar.now())) {
                // A checkpoint is optional: its I/O failure must not invalidate an already committed batch.
                try { inputs.findTopBySessionDateOrderByIdDesc(date)
                        .ifPresent(anchor -> checkpoints.save(date, calendar.now(), anchor, engine)); }
                catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Intraday checkpoint skipped ({})", failure.getClass().getSimpleName());
                }
            }
            for (Signal signal : created) events.publish("intraday.entry-ready", Map.of("id", signal.id().toString(),
                    "stockCode", signal.stockCode(), "signal", signal));
        } catch (RuntimeException failure) {
            // Rolled-back in-memory state must never leak into the next committed outcome.
            engine = null; committedOutcomes.clear(); recoveryPending.clear(); dropped.addAndGet(batch.size()); error = "INTRADAY_PROCESSING_FAILED";
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Intraday batch failed ({})", failure.getClass().getSimpleName());
        }
    }

    private void recover() {
        committedOutcomes.clear();
        LocalDate today = calendar.today();
        transaction.executeWithoutResult(ignored -> {
            // Finalize previous observed day without substituting a current quote for its missing close.
            for (var entity : recommendations.findByTrackingCompleteFalse()) {
                Result r = result(entity); IntradayTracker.advance(r, calendar.now());
                entity.updateOutcome(json(r.outcome()), r.outcome().trackingComplete);
            }
        });
        var saved = recommendations.findBySessionDateOrderByRecommendedAtAsc(today);
        boolean finishedSession = calendar.now().isAfter(calendar.close(today).plus(policy.maxDelay()))
                && saved.stream().map(this::result).allMatch(r -> r.outcome().trackingComplete);
        // Completed paper outcomes are authoritative; no rolling setup history is needed after the session.
        engine = new IntradayEngine(calendar);
        if (!finishedSession) {
            var checkpoint = checkpoints.load(today, calendar.now(), inputs);
            long cursor = 0;
            if (checkpoint.isPresent()) { engine.restoreState(checkpoint.get().state()); cursor = checkpoint.get().cursor(); }
            replayInto(engine, today, null, cursor);
        }
        // Signals/outcomes are the authoritative paper record, including expiry during periods with no ticks.
        for (var row : saved) {
            Result restored = result(row);
            engine.restore(restored);
            committedOutcomes.put(row.getId(), json(restored.outcome()));
        }
        date = today;
        recoveryPending.clear(); recoveryPending.addAll(engine.observedSymbols());
    }

    private Map<UUID, String> changedOutcomes(IntradayEngine target) {
        Map<UUID, String> changed = new HashMap<>();
        for (Result result : target.results()) {
            String outcome = json(result.outcome());
            if (!outcome.equals(committedOutcomes.get(result.signal().id()))) changed.put(result.signal().id(), outcome);
        }
        return changed;
    }

    private void persist(IntradayEngine target, Map<UUID, String> changed) {
        if (changed.isEmpty()) return;
        Map<UUID, IntradayRecommendationEntity> existing = new HashMap<>();
        recommendations.findAllById(changed.keySet())
                .forEach(row -> existing.put(row.getId(), row));
        for (Result result : target.results()) {
            String outcome = changed.get(result.signal().id());
            if (outcome == null) continue;
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
        status.put("sourceLossEvents", sourceLossEvents.get()); status.put("checkpoint", checkpoints.status());
        status.put("recoveryPending", Set.copyOf(recoveryPending));
        status.put("recovery", engine == null ? Map.of() : engine.recoveryStatus());
        var candidates = engine == null ? List.<Candidate>of() : List.copyOf(engine.candidates());
        Set<String> eligibleCodes = new HashSet<>();
        if (!candidates.isEmpty()) stocks.findByStockCodeIn(candidates.stream().map(Candidate::stockCode).toList())
                .stream().filter(stock -> stock.isActive() && !stock.isManaged() && !stock.isTradingHalted()
                        && stock.getMarketType() == com.sunmo.stockplatform.stock.domain.MarketType.STOCK)
                .forEach(stock -> eligibleCodes.add(stock.getStockCode()));
        status.put("candidates", candidates.stream()
                .filter(candidate -> eligibleCodes.contains(candidate.stockCode())
                        && !"UNIVERSE_EXCLUDED".equals(candidate.reason())).toList());
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
    public Map<String, Object> compareExits(LocalDate date, IntradayExitComparison.Request request) {
        Instant asOf = calendar.now();
        if (date.isAfter(calendar.today())) throw new IllegalArgumentException("Future session is not replayable");
        var comparison = new IntradayExitComparison(list(date).stream()
                .filter(r -> !r.signal().recommendedAt().isAfter(asOf)).toList(), request);
        // Bound the scan at request start, so a live ingestion stream cannot extend this research request.
        long upper = inputs.findTopBySessionDateOrderByIdDesc(date).map(IntradayInputEntity::getId).orElse(0L);
        long after = 0;
        while (after < upper) {
            var page = inputs.findBySessionDateAndIdGreaterThanOrderById(date, after, PageRequest.of(0, 2000));
            if (page.isEmpty()) break;
            for (var row : page) {
                if (row.getId() > upper) break;
                Input input = read(row.getPayload(), Input.class);
                if (!input.evaluatedAt().isAfter(asOf)) comparison.accept(input);
                after = row.getId();
            }
            if (page.getLast().getId() >= upper) break;
        }
        return comparison.finish(asOf);
    }
    private IntradayEngine replayEngine(LocalDate day, IntradayProperties override) {
        IntradayEngine target = new IntradayEngine(calendar);
        replayInto(target, day, override, 0);
        return target;
    }
    private void replayInto(IntradayEngine target, LocalDate day, IntradayProperties override, long after) {
        while (true) {
            var page = inputs.findBySessionDateAndIdGreaterThanOrderById(day, after, PageRequest.of(0, 2000));
            if (page.isEmpty()) return;
            for (var row : page) {
                Input input = read(row.getPayload(), Input.class);
                if (override != null) input = new Input(input.observation(), input.evaluatedAt(), input.stockName(),
                        input.eligible(), input.dataLoss(), override, input.recoveryContinuation());
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
