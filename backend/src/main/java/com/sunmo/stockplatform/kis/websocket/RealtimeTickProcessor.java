/*
 * WebSocket에서 받은 체결을 별도 작업 스레드 하나의 대기열(큐)에 넣고, 들어온 순서대로 MarketDataService에 넘겨 처리해요.
 * 대기열이 가득 차거나 연결이 끊기면 "데이터 손실 세대" 번호를 올려, 다음 체결 처리 때 손실이 있었음을 함께 알려요.
 * KisRealtimeClient가 내부에서 만들어 사용해요(패키지 밖에서는 보이지 않아요).
 */
package com.sunmo.stockplatform.kis.websocket;

import com.sunmo.stockplatform.market.application.MarketDataService;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.market.domain.MarketTick;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One FIFO consumer: never block the socket callback or reorder ticks to gain throughput.
 * (작업자 한 명이 먼저 온 순서대로만 처리해요. 빨리 처리하려고 소켓 콜백을 막거나 체결 순서를 바꾸지 않아요.)
 * 은행 창구가 하나뿐이라 번호표 순서대로만 처리하고, 대기 의자가 꽉 차면 새 손님을 돌려보내며 "누락 있음"을 적어 두는 것과 같아요.
 */
final class RealtimeTickProcessor implements AutoCloseable {
    private final MarketDataService market;
    private final RealtimeDiagnostics diagnostics;
    private final ThreadPoolExecutor worker;
    private final AtomicLong lossGeneration = new AtomicLong();
    private final java.util.Map<String, Long> observedGeneration = new java.util.HashMap<>();
    private final java.util.Map<String, Long> observedFailures = new java.util.HashMap<>();
    private long failureGeneration;

    /**
     * 체결 처리용 작업 스레드 1개와 크기가 정해진 대기열을 만들어요.
     * 받는 것: 시세 처리 서비스, 실시간 진단 기록기, 대기열 크기(1~100000).
     * 돌려주는 것: 처리기 객체. 대기열이 가득 차면 기다리지 않고 거절(AbortPolicy)하도록 설정해요.
     */
    RealtimeTickProcessor(MarketDataService market, RealtimeDiagnostics diagnostics, int capacity) {
        if (capacity < 1 || capacity > 100000) throw new IllegalArgumentException("Invalid realtime processing queue capacity");
        this.market = market;
        this.diagnostics = diagnostics;
        // 데몬 스레드(서버가 꺼질 때 같이 끝나는 보조 스레드)로 만들어 종료를 막지 않게 해요.
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity), task -> {
            Thread thread = new Thread(task, "kis-tick-processing"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        // 진단 화면에서 현재 대기열 길이를 볼 수 있게 등록해요.
        diagnostics.processingQueue(() -> worker.getQueue().size());
    }

    /**
     * 체결 한 건을 대기열에 넣어요. WebSocket 콜백 스레드를 막지 않도록 바로 돌아와요.
     * 받는 것: 체결(tick), 서버 수신시각(receivedAt). 돌려주는 것: 없음.
     * 대기열이 가득 차면 체결을 버리고, 버림 횟수와 손실 세대 번호를 올려요.
     */
    void submit(MarketTick tick, Instant receivedAt) {
        long queuedAt = System.nanoTime();
        long generation = lossGeneration.get();
        try { worker.execute(() -> process(tick, receivedAt, queuedAt, generation)); }
        catch (RejectedExecutionException rejected) { diagnostics.droppedTick(); lossGeneration.incrementAndGet(); }
    }

    /** 연결이 끊겼다고 알려 손실 세대 번호를 올려요. 다음 체결부터 "중간에 빠진 데이터가 있을 수 있음"으로 표시돼요. */
    void disconnected() { lossGeneration.incrementAndGet(); }

    /**
     * 작업 스레드에서 체결 한 건을 실제로 처리해요.
     * 받는 것: 체결, 서버 수신시각, 대기열에 넣은 시각(나노초), 넣을 때의 손실 세대 번호.
     * 돌려주는 것: 없음. 처리 실패는 예외를 밖으로 던지지 않고 진단에 기록해요.
     */
    private void process(MarketTick tick, Instant receivedAt, long queuedAt, long generation) {
        // 준비: 대기열 대기 시간과 원천 발생시각→수신시각 지연을 기록해요.
        diagnostics.timing("processingQueueWait", System.nanoTime() - queuedAt);
        diagnostics.timing("sourceToCallback", Duration.between(tick.occurredAt(), receivedAt).toNanos());
        long start = System.nanoTime();
        // 처리: 이 종목이 마지막으로 처리된 뒤 손실(버림·끊김)이나 처리 실패가 있었다면 dataLoss=true로 넘겨요.
        // 세대 기록용 Map은 작업 스레드 하나만 만지므로 동기화 없이 써요.
        long previous = observedGeneration.getOrDefault(tick.stockCode(), 0L);
        try {
            long bodyNanos = market.onTick(tick, receivedAt, generation > previous
                    || failureGeneration > observedFailures.getOrDefault(tick.stockCode(), 0L));
            diagnostics.timing("transactionBoundary", System.nanoTime() - start - bodyNanos);
            // 성공했을 때만 이 종목의 "어디까지 확인했는지" 세대 번호를 갱신해요.
            observedGeneration.put(tick.stockCode(), generation);
            observedFailures.put(tick.stockCode(), failureGeneration);
        } catch (RuntimeException error) {
            // 실패하면 실패 세대를 올려, 이후 체결들이 dataLoss로 표시되게 해요.
            failureGeneration++;
            diagnostics.processingFailed(tick.stockCode(), error.getClass().getSimpleName());
        } finally {
            // Legacy metric name: full processing, including conditional candle transactions.
            diagnostics.timing("tickTransaction", System.nanoTime() - start);
        }
    }

    /**
     * 처리기를 멈춰요. 받는 것: 없음. 돌려주는 것: 없음.
     * 남은 작업을 최대 5초 기다리고, 그래도 남은 체결은 버린 것으로 진단에 기록해요.
     */
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
