package com.sunmo.stockplatform.kis.websocket;

import com.sunmo.stockplatform.market.application.MarketDataService;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.market.domain.MarketTick;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** One FIFO consumer: never block the socket callback or reorder ticks to gain throughput. */
final class RealtimeTickProcessor implements AutoCloseable {
    private final MarketDataService market;
    private final RealtimeDiagnostics diagnostics;
    private final ThreadPoolExecutor worker;
    private final AtomicLong lossGeneration = new AtomicLong();
    private final java.util.Map<String, Long> observedGeneration = new java.util.HashMap<>();
    private final java.util.Map<String, Long> observedFailures = new java.util.HashMap<>();
    private long failureGeneration;

    RealtimeTickProcessor(MarketDataService market, RealtimeDiagnostics diagnostics, int capacity) {
        if (capacity < 1 || capacity > 100000) throw new IllegalArgumentException("Invalid realtime processing queue capacity");
        this.market = market;
        this.diagnostics = diagnostics;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity), task -> {
            Thread thread = new Thread(task, "kis-tick-processing"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        diagnostics.processingQueue(() -> worker.getQueue().size());
    }

    void submit(MarketTick tick, Instant receivedAt) {
        long queuedAt = System.nanoTime();
        long generation = lossGeneration.get();
        try { worker.execute(() -> process(tick, receivedAt, queuedAt, generation)); }
        catch (RejectedExecutionException rejected) { diagnostics.droppedTick(); lossGeneration.incrementAndGet(); }
    }

    void disconnected() { lossGeneration.incrementAndGet(); }

    private void process(MarketTick tick, Instant receivedAt, long queuedAt, long generation) {
        diagnostics.timing("processingQueueWait", System.nanoTime() - queuedAt);
        diagnostics.timing("sourceToCallback", Duration.between(tick.occurredAt(), receivedAt).toNanos());
        long start = System.nanoTime();
        long previous = observedGeneration.getOrDefault(tick.stockCode(), 0L);
        try {
            long bodyNanos = market.onTick(tick, receivedAt, generation > previous
                    || failureGeneration > observedFailures.getOrDefault(tick.stockCode(), 0L));
            diagnostics.timing("transactionBoundary", System.nanoTime() - start - bodyNanos);
            observedGeneration.put(tick.stockCode(), generation);
            observedFailures.put(tick.stockCode(), failureGeneration);
        } catch (RuntimeException error) {
            failureGeneration++;
            diagnostics.processingFailed(tick.stockCode(), error.getClass().getSimpleName());
        } finally {
            // Legacy metric name: full processing, including conditional candle transactions.
            diagnostics.timing("tickTransaction", System.nanoTime() - start);
        }
    }

    @Override public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS))
                worker.shutdownNow().forEach(task -> diagnostics.droppedTick());
        } catch (InterruptedException interrupted) {
            worker.shutdownNow().forEach(task -> diagnostics.droppedTick());
            Thread.currentThread().interrupt();
        }
    }
}
