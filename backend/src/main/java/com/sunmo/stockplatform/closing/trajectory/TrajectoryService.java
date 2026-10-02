package com.sunmo.stockplatform.closing.trajectory;

import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.*;

@Service
public class TrajectoryService {
    private final TrajectoryProperties policy;
    private final MinuteFeatureAggregator aggregator;
    private final TrajectoryStore store;
    private final StockRepository stocks;
    private final StockCandleRepository candles;
    private final ClosingTradingCalendar calendar;
    private final TransactionTemplate transaction;
    private final ArrayBlockingQueue<Context> contexts = new ArrayBlockingQueue<>(5000);
    private final AtomicLong droppedContexts = new AtomicLong();
    private String error;
    private final MicrostructureService microstructure;
    public TrajectoryService(TrajectoryProperties policy, TrajectoryStore store, StockRepository stocks,
            StockCandleRepository candles, ClosingTradingCalendar calendar, PlatformTransactionManager transactions, MicrostructureService microstructure) {
        this.policy = policy; this.store = store; this.stocks = stocks; this.candles = candles; this.calendar = calendar;
        aggregator = new MinuteFeatureAggregator(policy.watermark()); transaction = new TransactionTemplate(transactions);
        this.microstructure = microstructure;
    }
    @TransactionalEventListener
    public void receive(ObservedMarketTick event) {
        if (policy.enabled() && calendar.isTradingDay(event.tick().businessDate())) aggregator.accept(event);
    }
    public void context(Context row) { if (policy.enabled() && !contexts.offer(row)) droppedContexts.incrementAndGet(); }

    @Scheduled(fixedDelayString = "${closing.trajectory.flush-interval:1s}", scheduler = "trajectoryScheduler")
    public synchronized void flush() {
        if (!policy.enabled()) return;
        microstructure.flush();
        Instant now = calendar.now();
        List<Minute> ready = aggregator.ready(now);
        List<Context> batch = new ArrayList<>(); contexts.drainTo(batch);
        try {
            transaction.executeWithoutResult(status -> {
                batch.forEach(store::context); ready.forEach(store::minute);
                for (Minute row : ready) {
                    Instant cutoff = row.start().plusSeconds(60);
                    var local = cutoff.atZone(ClosingTradingCalendar.ZONE);
                    if (local.toLocalTime().isBefore(policy.analysisStart()) || local.toLocalTime().isAfter(policy.analysisEnd())
                            || Duration.between(policy.analysisStart(), local.toLocalTime()).toMinutes() % policy.snapshotMinutes() != 0) continue;
                    var stock = stocks.findByStockCode(row.symbol()).orElse(null);
                    if (stock == null) continue;
                    var daily = candles.findTop61ByStockIdAndTimeframeAndStartTimeLessThanEqualAndFinalCandleTrueOrderByStartTimeDesc(
                            stock.getId(), "1D", local.toLocalDate().atStartOfDay(ClosingTradingCalendar.ZONE).toInstant().minusSeconds(1));
                    // Delayed finalization never fabricates historical availability.
                    Snapshot snapshot = TrajectoryFeatures.calculate(row.symbol(), stock.getMarket().name(), cutoff, now,
                            store.minutes(row.symbol(), calendar.open(local.toLocalDate()), cutoff),
                            store.contexts(row.symbol(), stock.getMarket().name(), calendar.open(local.toLocalDate()), cutoff),
                            availableDaily(daily, local.toLocalDate(), now), policy);
                    if (snapshot != null) snapshot = microstructure.enrich(snapshot).completedAt(calendar.now());
                    if (snapshot != null && store.snapshot(snapshot))
                        org.slf4j.LoggerFactory.getLogger(getClass()).info("[ClosingCandidate] symbol={} cutoff={} mode=SHADOW price={} turnover={} execution={} vwap={} patterns={} catalyst=UNKNOWN",
                                row.symbol(), cutoff, snapshot.price(), snapshot.turnover(), snapshot.execution(), snapshot.vwap(), snapshot.intradayPattern());
                }
            });
            aggregator.acknowledge(ready); error = null;
        } catch (RuntimeException failure) {
            batch.forEach(this::context); error = "TRAJECTORY_PERSIST_FAILED";
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Trajectory persistence failed ({})", failure.getClass().getSimpleName());
        }
    }
    public synchronized Map<String, Object> status() {
        return fields("enabled", policy.enabled(), "mode", "SHADOW", "rejectedTicks", aggregator.rejected(),
                "droppedContexts", droppedContexts.get(), "pendingContexts", contexts.size(), "error", error);
    }
    private List<com.sunmo.stockplatform.candle.domain.StockCandle> availableDaily(
            List<com.sunmo.stockplatform.candle.domain.StockCandle> rows, LocalDate date, Instant availableBy) {
        var valid = rows.stream().filter(c -> c.isFinalCandle() && c.getCreatedAt() != null && c.getUpdatedAt() != null
                        && !c.getCreatedAt().isAfter(availableBy) && !c.getUpdatedAt().isAfter(availableBy))
                .sorted(Comparator.comparing(com.sunmo.stockplatform.candle.domain.StockCandle::getStartTime).reversed()).toList();
        List<com.sunmo.stockplatform.candle.domain.StockCandle> result = new ArrayList<>();
        LocalDate expected = calendar.previousTradingDay(date);
        for (var candle : valid) {
            if (!candle.getStartTime().atZone(ClosingTradingCalendar.ZONE).toLocalDate().equals(expected)) break;
            result.add(candle); expected = calendar.previousTradingDay(expected);
        }
        return result;
    }
}
