/*
 * 실시간 수집 파이프라인의 상태와 통계를 메모리에 모아 두는 진단 기록부예요.
 * WebSocket 연결, 받은 틱 수, 파싱·처리 오류, 구독 상태, 단계별 처리 시간, 처리 큐 상황, 봉 공백·보충 결과 등을 기록해요.
 * KIS 실시간 클라이언트·틱 처리기·MarketDataService·Scanner·보충 서비스가 값을 쓰고, RealtimeStatusController가 snapshot으로 읽어 보여 줘요.
 */
package com.sunmo.stockplatform.market.application;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 실시간 수집의 블랙박스(운행 기록 장치) 같은 클래스예요.
 * 여러 부품이 "연결됐다", "틱 받았다", "오류 났다", "이 단계에 몇 ms 걸렸다"를 적어 두면, 점검할 때 한 장으로 꺼내 볼 수 있어요.
 * 모든 값은 메모리에만 있어서 서버를 재시작하면 0부터 다시 세요.
 */
@Component
public class RealtimeDiagnostics {
    /** 종목별 구독 진행 상태예요. QUEUED(연결 대기) → REQUESTED(KIS에 요청함) → SUBSCRIBED(성공) 또는 FAILED(실패). */
    public enum SubscriptionState {
        QUEUED, REQUESTED, SUBSCRIBED, FAILED
    }

    /** 종목 하나의 구독 상태, 설명 메시지, 상태가 바뀐 시각이에요. */
    public record SubscriptionStatus(String stockCode, SubscriptionState state, String message, Instant updatedAt) {
    }

    /** 종목 하나의 분봉 보충(backfill) 결과예요. state는 RUNNING/COMPLETED/FAILED, gaps는 빈 봉 수, saved는 채운 봉 수예요. */
    public record BackfillStatus(String stockCode, String state, int gaps, int saved, String message,
            Instant updatedAt) {
    }

    /**
     * 진단 정보 전체를 한 시점에 찍은 사진이에요. /api/v1/market/realtime/status 응답의 realtime 부분이 돼요.
     * lastTickAt·lastMessageAt 등은 서버 수신 기준 시각이고, last...SourceAt은 틱의 원천 체결시각이에요.
     */
    public record Snapshot(
            boolean connected,
            Instant connectedAt,
            Instant disconnectedAt,
            Instant lastMessageAt,
            Instant lastPingAt,
            Instant lastTickAt,
            long receivedFrames,
            long receivedTicks,
            long parseErrors,
            long processingErrors,
            long subscriptionRequests,
            long subscriptionSuccesses,
            long subscriptionFailures,
            Instant lastCandleAt,
            Instant lastScannerEvaluationAt,
            long detections,
            int pendingPerformances,
            Instant lastPerformanceFlushAt,
            int lastPerformanceFlushSize,
            Instant lastFeatureAt,
            long featureSnapshots,
            int featureTrackedStocks,
            Map<String, Integer> candleGaps,
            Map<String, BackfillStatus> backfills,
            java.util.List<String> recentErrors,
            Map<String, SubscriptionStatus> subscriptions,
            Map<String, Timing> timings, int processingQueued, long processingDropped,
            long outsideSessionTicks, Instant lastOutsideSessionSourceAt,
            long closingAuctionTicks, Instant lastClosingAuctionSourceAt,
            long scannerCooldownFallbacks, Instant lastScannerCooldownFallbackAt) {
    }

    /**
     * Averages stay clamped at zero; minMs/negativeCount keep raw values so host-vs-source clock skew stays visible.
     * 처리 단계 하나의 소요 시간 통계(ms)예요. 평균·최대·마지막 값은 음수를 0으로 잘라 계산하지만,
     * 최솟값과 음수 횟수는 원래 값을 남겨 서버 시계와 원천 시계의 어긋남이 보이게 해요.
     */
    public record Timing(long count, double averageMs, double maxMs, double lastMs, double minMs, long negativeCount) {}
    /** 한 단계의 소요 시간(나노초)을 누적하는 계산기예요. 여러 스레드가 쓰므로 synchronized로 보호해요. */
    private static class Samples {
        long count, total, max, last, min = Long.MAX_VALUE, negatives;
        /** 하는 일: 측정값 하나를 더해요. 받는 것: 나노초(음수일 수 있음). */
        synchronized void add(long raw) {
            long nanos = Math.max(0, raw);
            count++; total += nanos; max = Math.max(max, nanos); last = nanos;
            min = Math.min(min, raw); if (raw < 0) negatives++;
        }
        /** 하는 일: 지금까지의 누적값을 ms 단위 Timing으로 바꿔 돌려줘요. */
        synchronized Timing snapshot() {
            return new Timing(count, count == 0 ? 0 : total / (double) count / 1e6, max / 1e6, last / 1e6,
                    count == 0 ? 0 : min / 1e6, negatives);
        }
    }
    private final Map<String, Samples> timings = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile java.util.function.IntSupplier queueSize = () -> 0;
    private final AtomicLong processingDropped = new AtomicLong();
    /** 하는 일: 단계 이름별로 걸린 시간(나노초)을 기록해요. 받는 것: 단계 이름, 나노초. */
    public void timing(String stage, long nanos) { timings.computeIfAbsent(stage, key -> new Samples()).add(nanos); }
    /** 하는 일: 처리 큐에 지금 몇 건이 쌓였는지 물어볼 함수를 등록해요. 받는 것: 큐 크기를 돌려주는 함수. */
    public void processingQueue(java.util.function.IntSupplier size) { queueSize = size; }
    /** 하는 일: 처리 큐가 가득 차 틱 하나를 버렸다고 세요. */
    public void droppedTick() { processingDropped.incrementAndGet(); }
    private final AtomicLong outsideSessionTicks = new AtomicLong();
    private final AtomicLong closingAuctionTicks = new AtomicLong();
    private volatile Instant lastOutsideSessionSourceAt;
    private volatile Instant lastClosingAuctionSourceAt;
    /**
     * Parsed ticks rejected by the session window (source time), so a missing closing print is observable.
     * 하는 일: 정규장 시간 밖이라 버린 틱을 세고 그 원천시각을 남겨요. 종가 체결이 빠졌는지 확인할 때 써요.
     */
    public void outsideSessionTick(Instant sourceAt) { outsideSessionTicks.incrementAndGet(); lastOutsideSessionSourceAt = sourceAt; }
    /**
     * Accepted closing auction prints stamped after 15:30:00 (random end).
     * 하는 일: 15:30:00 이후로 찍혀 받아들인 종가 단일가 체결을 세고 원천시각을 남겨요.
     */
    public void closingAuctionTick(Instant sourceAt) { closingAuctionTicks.incrementAndGet(); lastClosingAuctionSourceAt = sourceAt; }
    private final AtomicLong scannerCooldownFallbacks = new AtomicLong();
    private volatile Instant lastScannerCooldownFallbackAt;
    /**
     * Scanner cooldown fell back to process memory; the error list gets at most one entry per minute.
     * 하는 일: Scanner 재알림 대기(cooldown) 저장이 Redis 대신 메모리로 대체된 횟수를 세요.
     * 오류 목록이 넘치지 않게 오류 메시지는 1분에 한 번만 남겨요. 받는 것: 대체 사유.
     */
    public void scannerCooldownFallback(String reason) {
        scannerCooldownFallbacks.incrementAndGet();
        Instant now = Instant.now();
        Instant previous = lastScannerCooldownFallbackAt;
        lastScannerCooldownFallbackAt = now;
        if (previous == null || !now.isBefore(previous.plus(Duration.ofMinutes(1))))
            error("Scanner cooldown fallback: " + reason);
    }

    private final Map<String, SubscriptionStatus> subscriptions = new ConcurrentSkipListMap<>();
    private final AtomicLong receivedFrames = new AtomicLong();
    private final AtomicLong receivedTicks = new AtomicLong();
    private final AtomicLong parseErrors = new AtomicLong();
    private final AtomicLong processingErrors = new AtomicLong();
    private final AtomicLong subscriptionRequests = new AtomicLong();
    private final AtomicLong subscriptionSuccesses = new AtomicLong();
    private final AtomicLong subscriptionFailures = new AtomicLong();
    private final AtomicLong detections = new AtomicLong();
    private final Map<String, Integer> candleGaps = new ConcurrentSkipListMap<>();
    private final Map<String, BackfillStatus> backfills = new ConcurrentSkipListMap<>();
    private final java.util.Deque<String> recentErrors = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private volatile boolean connected;
    private volatile Instant connectedAt;
    private volatile Instant disconnectedAt;
    private volatile Instant lastMessageAt;
    private volatile Instant lastPingAt;
    private volatile Instant lastTickAt;
    private volatile Instant lastCandleAt;
    private volatile Instant lastScannerEvaluationAt;
    private volatile int pendingPerformances;
    private volatile Instant lastPerformanceFlushAt;
    private volatile int lastPerformanceFlushSize;
    private final AtomicLong featureSnapshots = new AtomicLong();
    private volatile Instant lastFeatureAt;
    private volatile int featureTrackedStocks;

    /** 하는 일: KIS WebSocket 연결이 열렸다고 표시하고 연결 시각을 남겨요. */
    public void connected() {
        connected = true;
        connectedAt = Instant.now();
    }

    /** 하는 일: 연결이 끊겼다고 표시하고, 모든 종목 구독 상태를 "재연결 대기(QUEUED)"로 되돌려요. */
    public void disconnected() {
        connected = false;
        disconnectedAt = Instant.now();
        subscriptions.replaceAll((code, status) -> new SubscriptionStatus(code, SubscriptionState.QUEUED,
                "waiting for reconnect", Instant.now()));
    }

    /** 하는 일: WebSocket 메시지(프레임) 하나를 받았다고 세고 마지막 수신 시각을 갱신해요. */
    public void messageReceived() {
        receivedFrames.incrementAndGet();
        lastMessageAt = Instant.now();
    }

    /** 하는 일: KIS가 보낸 연결 유지 신호(PING)를 받은 시각을 남겨요. */
    public void pingReceived() {
        lastPingAt = Instant.now();
    }

    /**
     * A repeated registry add or a send racing the connect callback can queue a code that KIS already acknowledged.
     * While connected that must not hide the subscription; a real disconnect resets every code anyway.
     * 하는 일: 종목을 "연결 대기(QUEUED)"로 표시해요. 단, 연결 중이고 이미 SUBSCRIBED인 종목은 그대로 둬요.
     * 받는 것: 종목코드.
     */
    public void queued(String stockCode) {
        subscriptions.compute(stockCode, (code, current) -> connected && current != null
                && current.state() == SubscriptionState.SUBSCRIBED ? current
                : new SubscriptionStatus(code, SubscriptionState.QUEUED, "waiting for connection", Instant.now()));
    }

    /** 하는 일: KIS에 구독 요청을 보냈다고 세고 상태를 REQUESTED로 바꿔요. 받는 것: 종목코드. */
    public void subscriptionRequested(String stockCode) {
        subscriptionRequests.incrementAndGet();
        subscriptions.put(stockCode,
                new SubscriptionStatus(stockCode, SubscriptionState.REQUESTED, "waiting for KIS response",
                        Instant.now()));
    }

    /**
     * 하는 일: KIS 구독 응답을 반영해 성공/실패를 세고 상태를 SUBSCRIBED 또는 FAILED로 바꿔요.
     * 받는 것: 종목코드, 성공 여부, KIS 메시지.
     */
    public void subscriptionAcknowledged(String stockCode, boolean success, String message) {
        if (success)
            subscriptionSuccesses.incrementAndGet();
        else
            subscriptionFailures.incrementAndGet();
        subscriptions.put(stockCode, new SubscriptionStatus(stockCode,
                success ? SubscriptionState.SUBSCRIBED : SubscriptionState.FAILED, message, Instant.now()));
    }

    /** 하는 일: 메시지 하나에 담긴 틱 개수를 더하고 마지막 틱 수신 시각(서버 기준)을 갱신해요. */
    public void ticksReceived(int count) {
        receivedTicks.addAndGet(count);
        lastTickAt = Instant.now();
    }

    /** 하는 일: KIS 실시간 메시지 파싱 실패를 세고 오류 목록에 남겨요. 받는 것: 실패 사유. */
    public void parseFailed(String reason) {
        parseErrors.incrementAndGet();
        error("KIS realtime parse failed: " + reason);
    }

    /** 하는 일: 틱 처리 중 실패를 세고 오류 목록에 남겨요. 받는 것: 종목코드, 실패 사유. */
    public void processingFailed(String stockCode, String reason) {
        processingErrors.incrementAndGet();
        error("Realtime tick processing failed for " + stockCode + ": " + reason);
    }

    /**
     * 하는 일: 연결은 돼 있는데 오래 아무 메시지도 안 왔는지(멈춘 연결인지) 판단해요.
     * 받는 것: 현재 시각, 허용 대기 시간.
     * 돌려주는 것: 마지막 메시지(없으면 연결 시각) 이후 timeout이 지났으면 true.
     */
    public boolean isConnectionStale(Instant now, Duration timeout) {
        Instant latest = lastMessageAt == null ? connectedAt : lastMessageAt;
        if (connectedAt != null && (latest == null || connectedAt.isAfter(latest))) latest = connectedAt;
        return connected && latest != null && latest.plus(timeout).isBefore(now);
    }

    /** 하는 일: 마지막으로 틱을 받은 서버 시각을 돌려줘요(원천 체결시각이 아니에요). */
    public Instant lastTickAt() {
        return lastTickAt;
    }

    /**
     * 하는 일: 구독 해제 응답을 반영해요. 성공이면 상태 목록에서 지우고, 실패면 FAILED로 남겨요.
     * 받는 것: 종목코드, 성공 여부, KIS 메시지.
     */
    public void subscriptionRemoved(String stockCode, boolean success, String message) {
        if (success)
            subscriptions.remove(stockCode);
        else
            subscriptionAcknowledged(stockCode, false, message);
    }

    /** 하는 일: 마지막으로 저장한 확정봉의 시작시각을 남겨요. */
    public void candlePersisted(Instant startTime) {
        lastCandleAt = startTime;
    }

    /** 하는 일: Scanner가 마지막으로 평가한 서버 시각을 남겨요. */
    public void scannerEvaluated() {
        lastScannerEvaluationAt = Instant.now();
    }

    /** 하는 일: 새 탐지(detection)가 만들어졌다고 세요. */
    public void detectionCreated() {
        detections.incrementAndGet();
    }

    /** 하는 일: 아직 DB에 쓰지 않은 성과 추적 건수를 기록해요. */
    public void pendingPerformances(int count) {
        pendingPerformances = count;
    }

    /** 하는 일: 성과 추적 데이터를 DB에 몰아서 쓴(flush) 시각과 건수를 남겨요. */
    public void performanceFlushed(int count) {
        lastPerformanceFlushAt = Instant.now();
        lastPerformanceFlushSize = count;
    }

    /** 하는 일: Feature 스냅샷 하나를 만들었다고 세고, 추적 중인 종목 수를 남겨요. */
    public void featureSnapshot(int trackedStocks) {
        lastFeatureAt = Instant.now();
        featureTrackedStocks = trackedStocks;
        featureSnapshots.incrementAndGet();
    }

    /** 하는 일: 종목의 빈 5분봉 개수를 기록해요. */
    public void candleGaps(String stockCode, int count) {
        candleGaps.put(stockCode, count);
    }

    /** 하는 일: 종목의 분봉 보충을 시작했다고(RUNNING) 기록해요. 받는 것: 종목코드, 빈 봉 수. */
    public void backfillStarted(String stockCode, int gaps) {
        backfills.put(stockCode, new BackfillStatus(stockCode, "RUNNING", gaps, 0, null, Instant.now()));
    }

    /** 하는 일: 분봉 보충 성공을 기록하고, 남은 빈 봉 수(빈 봉 - 저장 수)를 갱신해요. */
    public void backfillSucceeded(String stockCode, int gaps, int saved) {
        backfills.put(stockCode, new BackfillStatus(stockCode, "COMPLETED", gaps, saved, null, Instant.now()));
        candleGaps.put(stockCode, Math.max(0, gaps - saved));
    }

    /** 하는 일: 분봉 보충 실패를 기록하고 오류 목록에도 남겨요. */
    public void backfillFailed(String stockCode, int gaps, String message) {
        backfills.put(stockCode, new BackfillStatus(stockCode, "FAILED", gaps, 0, message, Instant.now()));
        error("Backfill " + stockCode + ": " + message);
    }

    /** 하는 일: 오류 메시지를 시각과 함께 맨 앞에 넣어요. 최근 20개만 보관해요. */
    public void error(String message) {
        recentErrors.addFirst(Instant.now() + " " + message);
        while (recentErrors.size() > 20)
            recentErrors.pollLast();
    }

    /**
     * 하는 일: 지금까지 모은 모든 진단 값을 바꿀 수 없는 복사본으로 묶어 돌려줘요.
     * 받는 것: 없음. 돌려주는 것: Snapshot.
     */
    public Snapshot snapshot() {
        return new Snapshot(connected, connectedAt, disconnectedAt, lastMessageAt, lastPingAt, lastTickAt,
                receivedFrames.get(), receivedTicks.get(), parseErrors.get(), processingErrors.get(), subscriptionRequests.get(),
                subscriptionSuccesses.get(), subscriptionFailures.get(), lastCandleAt, lastScannerEvaluationAt,
                detections.get(), pendingPerformances, lastPerformanceFlushAt, lastPerformanceFlushSize,
                lastFeatureAt, featureSnapshots.get(), featureTrackedStocks,
                Map.copyOf(candleGaps), Map.copyOf(backfills), java.util.List.copyOf(recentErrors),
                Map.copyOf(subscriptions), timingSnapshot(), queueSize.getAsInt(), processingDropped.get(),
                outsideSessionTicks.get(), lastOutsideSessionSourceAt, closingAuctionTicks.get(), lastClosingAuctionSourceAt,
                scannerCooldownFallbacks.get(), lastScannerCooldownFallbackAt);
    }

    /** 하는 일: 단계별 시간 통계를 모아 바꿀 수 없는 복사본으로 돌려줘요(Map.copyOf라 이름 순서는 보장되지 않아요). */
    private Map<String, Timing> timingSnapshot() {
        Map<String, Timing> result = new java.util.TreeMap<>();
        timings.forEach((name, samples) -> result.put(name, samples.snapshot()));
        return Map.copyOf(result);
    }
}
