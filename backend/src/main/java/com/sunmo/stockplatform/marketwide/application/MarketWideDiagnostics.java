/*
 * 시장 전체 스캔이 언제 시작·완료·실패·건너뛰기 되었는지를 메모리에 모아 두는 파일이에요.
 * 랭킹 출처별 시도/성공 누적 횟수도 세서, 운영 화면에서 스캔 상태를 확인할 수 있게 해요.
 * MarketWideScanCoordinator, MarketWideScanScheduler가 기록하고 진단 API가 읽어 가요.
 */
package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.BroadScanResponse;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 시장 전체 스캔의 운영 상태 기록판이에요.
 * 경기장 전광판처럼 마지막 시작/종료 시각, 성공·실패 횟수, 건너뛴 이유를 실시간으로 띄워 두는 역할을 해요.
 * 값은 서버 메모리에만 있어서 재시작하면 초기화돼요.
 */
@Component
public class MarketWideDiagnostics {
    private final AtomicLong completedRuns = new AtomicLong();
    private final AtomicLong failedRuns = new AtomicLong();
    private final AtomicLong skippedRuns = new AtomicLong();
    private volatile boolean running;
    private volatile Instant lastStartedAt;
    private volatile Instant lastCompletedAt;
    private volatile Instant lastFailedAt;
    private volatile Instant lastScheduledBucket;
    private volatile String lastSkipReason;
    private volatile String lastError;
    private volatile long lastDurationMillis;
    private volatile int lastScannedCount;
    private volatile int lastCandidateCount;
    private volatile boolean lastFallback;
    private volatile List<Source> rankingSources = List.of();
    private volatile com.sunmo.stockplatform.marketwide.api.MarketWideDtos.CollectionSummary collection;
    // 랭킹 출처별 누적 [시도 횟수, 성공 횟수]. synchronized인 completed()에서만 바꿔요.
    private final java.util.Map<String, long[]> sourceTotals = new java.util.HashMap<>();

    /**
     * 하는 일: 스캔 시작을 기록하고 직전 오류를 지워요.
     * 받는 것: 시작 시각, 회차 시각(수동 스캔이면 null).
     * 돌려주는 것: 없음.
     */
    public void started(Instant at, Instant bucket) {
        running = true;
        lastStartedAt = at;
        lastScheduledBucket = bucket;
        lastError = null;
    }

    /**
     * 하는 일: 스캔 완료를 기록하고, 결과 개수와 랭킹 출처별 누적 시도/성공 횟수를 갱신해요.
     * 받는 것: 완료 시각, 스캔 결과 응답.
     * 돌려주는 것: 없음.
     */
    public synchronized void completed(Instant at, BroadScanResponse response) {
        running = false;
        lastCompletedAt = at;
        lastDurationMillis = duration(at);
        lastScannedCount = response.scannedCount();
        lastCandidateCount = response.candidateCount();
        lastFallback = response.fallback();
        // 출처마다 누적 카운터를 올리고, 이번 결과와 누적값을 함께 담은 Source로 바꿔요.
        rankingSources = response.rankingSources().stream()
                .map(source -> {
                    long[] counts = sourceTotals.computeIfAbsent(source.type(), ignored -> new long[2]);
                    counts[0]++;
                    if (source.success()) counts[1]++;
                    return new Source(source.type(), source.success(), source.candidateCount(), source.error(), counts[0], counts[1]);
                })
                .toList();
        collection = response.collection();
        completedRuns.incrementAndGet();
    }

    /**
     * 하는 일: 스캔 실패를 기록해요.
     * 받는 것: 실패 시각, 오류 메시지.
     * 돌려주는 것: 없음.
     */
    public void failed(Instant at, String error) {
        running = false;
        lastFailedAt = at;
        lastDurationMillis = duration(at);
        lastError = error;
        failedRuns.incrementAndGet();
    }

    /**
     * 하는 일: 스캔을 건너뛴 이유를 기록해요.
     * 받는 것: 사유(WEEKEND 등), 회차 시각(없으면 null이고 이때는 마지막 회차 값을 유지해요).
     * 돌려주는 것: 없음.
     */
    public void skipped(String reason, Instant bucket) {
        lastSkipReason = reason;
        if (bucket != null)
            lastScheduledBucket = bucket;
        skippedRuns.incrementAndGet();
    }

    /**
     * 하는 일: 보강 큐 정보 없이 현재 진단 상태를 묶어 돌려줘요.
     * 받는 것: 없음.
     * 돌려주는 것: 진단 스냅샷(그 순간의 상태 사본).
     */
    public Snapshot snapshot() {
        return snapshot(null);
    }

    /**
     * 하는 일: 현재 진단 상태에 보강 큐 상태를 더해 한 덩어리로 돌려줘요.
     * 받는 것: 보강 큐 스냅샷(없으면 null).
     * 돌려주는 것: 진단 스냅샷.
     */
    public Snapshot snapshot(BroadEnrichmentQueue.Snapshot enrichment) {
        return new Snapshot(running, lastStartedAt, lastCompletedAt, lastFailedAt, lastScheduledBucket,
                completedRuns.get(), failedRuns.get(), skippedRuns.get(), lastSkipReason, lastError,
                lastDurationMillis, lastScannedCount, lastCandidateCount, lastFallback, rankingSources, collection, enrichment);
    }

    /**
     * 하는 일: 마지막 시작 시각부터 주어진 시각까지 걸린 시간을 밀리초로 계산해요.
     * 받는 것: 끝난 시각.
     * 돌려주는 것: 걸린 밀리초(시작 기록이 없으면 0, 음수는 0으로 막아요).
     */
    private long duration(Instant at) {
        return lastStartedAt == null ? 0 : Math.max(0, Duration.between(lastStartedAt, at).toMillis());
    }

    /**
     * 랭킹 출처 하나의 이번 결과와 누적 통계예요. attempts/successes는 서버 시작 이후 누적값이에요.
     */
    public record Source(String type, boolean success, int candidateCount, String error, long attempts, long successes) {}
    /**
     * 진단 API로 내보내는 상태 묶음이에요. 마지막 실행 시각들, 누적 횟수, 마지막 결과, 보강 큐 상태를 담아요.
     */
    public record Snapshot(boolean running, Instant lastStartedAt, Instant lastCompletedAt, Instant lastFailedAt,
            Instant lastScheduledBucket, long completedRuns, long failedRuns, long skippedRuns,
            String lastSkipReason, String lastError, long lastDurationMillis, int lastScannedCount,
            int lastCandidateCount, boolean lastFallback, List<Source> rankingSources,
            com.sunmo.stockplatform.marketwide.api.MarketWideDtos.CollectionSummary collection,
            BroadEnrichmentQueue.Snapshot enrichment) {}
}
