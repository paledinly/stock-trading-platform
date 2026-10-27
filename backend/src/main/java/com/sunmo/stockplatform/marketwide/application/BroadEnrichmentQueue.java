/*
 * 스캔 때 시세가 덜 채워진 BROAD 후보를 대기열에 넣고, 1초마다 하나씩 시세를 보강하는 파일이에요.
 * 보강한 시세는 원래 스캔 스냅샷을 고치지 않고, 실제 완료 시각으로 새 스냅샷을 추가해요.
 * MarketWideScannerService가 대기열에 넣고, 진단 화면이 snapshot()으로 상태를 읽어요.
 */
package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.closing.application.MarketSessionHours;

import com.sunmo.stockplatform.market.config.BroadEnrichmentProperties;
import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.marketwide.domain.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/**
 * Bounded, single-process queue. No historical snapshot is rewritten by enrichment.
 * (크기 제한이 있고 서버 한 대 안에서만 도는 대기열이며, 보강이 과거 스냅샷을 다시 쓰지 않아요.)
 * 시세 보강 작업을 줄 세워 하나씩 처리하는 대기열이에요.
 * 은행 번호표 창구처럼, 같은 사람이 또 오면 새 번호표 없이 기존 표를 쓰고, 마감 시간이 지나면 남은 번호표는 무효 처리해요.
 * 상태는 메모리에만 있어서 서버를 재시작하면 사라져요.
 */
@Component
public class BroadEnrichmentQueue {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final BroadQuoteResolver quotes;
    private final BroadSnapshotService snapshots;
    private final BroadEnrichmentProperties properties;
    private final MarketSessionPolicy session;
    private final ClosingRecommendationProperties closing;
    private final Clock clock;
    // 종목코드 → 작업. 넣은 순서를 지켜서 먼저 들어온 작업부터 처리해요.
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    // 진단용 누적 카운터: 접수, 합침(중복), 성공, 재시도 소진, 만료, 거절.
    private long accepted, coalesced, succeeded, exhausted, expired, rejected;
    private Instant lastProcessedAt;
    private String lastError;

    /**
     * 하는 일: 스프링이 쓰는 기본 생성자예요. 시계는 시스템 UTC 시계를 써요.
     * 받는 것: 시세 선택기, 스냅샷 서비스, 보강 설정, 장 시간 정책, 종가 추천 설정.
     */
    @Autowired
    public BroadEnrichmentQueue(BroadQuoteResolver quotes, BroadSnapshotService snapshots,
            BroadEnrichmentProperties properties, MarketSessionPolicy session, ClosingRecommendationProperties closing) {
        this(quotes, snapshots, properties, session, closing, Clock.systemUTC());
    }
    /**
     * 하는 일: 시계까지 직접 받는 생성자예요. 테스트에서 시간을 고정할 때 쓰는 것으로 보여요.
     * 받는 것: 위 협력 객체들과 시계(Clock).
     */
    public BroadEnrichmentQueue(BroadQuoteResolver quotes, BroadSnapshotService snapshots,
            BroadEnrichmentProperties properties, MarketSessionPolicy session, ClosingRecommendationProperties closing, Clock clock) {
        this.quotes = quotes; this.snapshots = snapshots; this.properties = properties;
        this.session = session; this.closing = closing; this.clock = clock;
    }

    /**
     * 하는 일: 시세가 불완전한 수집 결과를 보강 대기열에 넣어요.
     * 이미 완전한 시세는 건너뛰고, 같은 종목이 대기 중이면 후보 정보만 최신으로 바꿔요(합침).
     * 보강 가능 시간이 아니거나 대기열이 꽉 차면 거절해요.
     * 받는 것: 스캔 수집 결과 목록.
     * 돌려주는 것: 없음.
     */
    public synchronized void enqueue(List<BroadSnapshotService.Capture> captures) {
        Instant now = clock.instant();
        prune(now);
        if (!properties.enabled()) return;
        for (var capture : captures) {
            if (capture.data() != null && capture.data().complete()) continue;
            if (!eligible(now)) { rejected++; continue; }
            String code = capture.candidate().stock().getStockCode();
            Job existing = jobs.get(code);
            if (existing != null) {
                // 실행 중이 아닌 대기 작업이면 최신 후보 정보로 바꿔 끼워요.
                if (!existing.terminal() && !existing.state.equals("RUNNING")) existing.candidate = capture.candidate();
                coalesced++;
                continue;
            }
            if (jobs.size() >= properties.capacity()) { rejected++; continue; }
            jobs.put(code, new Job(capture.candidate(), now));
            accepted++;
        }
    }

    /**
     * 하는 일: 스케줄러(정해진 간격마다 자동 실행하는 장치)가 기본 1초마다 불러, 대기 작업 하나를 처리해요.
     * 한 번에 하나만 실행하고, 실패하면 지수적으로 늘어나는 간격으로 재시도해요.
     * 받는 것: 없음.
     * 돌려주는 것: 없음. 결과는 작업 상태와 카운터에 남겨요.
     */
    @Scheduled(fixedDelayString = "${market.wide.enrichment.poll-interval:1s}", scheduler = "broadEnrichmentScheduler")
    public void processOne() {
        Job job;
        Instant now = clock.instant();
        // 준비(잠금 안): 오래된 작업 정리 → 실행 중인 작업이 있으면 대기 → 시간 밖이거나 날짜가 바뀐 작업은 만료 → 처리할 작업 하나 고르기.
        synchronized (this) {
            prune(now);
            if (!properties.enabled()) return;
            if (jobs.values().stream().anyMatch(item -> item.state.equals("RUNNING"))) return;
            for (Job pending : jobs.values()) {
                if (!pending.terminal() && !pending.state.equals("RUNNING")
                        && (!eligible(now) || !pending.queuedAt.atZone(SEOUL).toLocalDate().equals(now.atZone(SEOUL).toLocalDate()))) {
                    pending.state = "EXPIRED"; expired++;
                }
            }
            if (!eligible(now)) return;
            job = jobs.values().stream().filter(item -> !item.terminal() && !item.state.equals("RUNNING")
                    && !item.nextAttemptAt.isAfter(now)).findFirst().orElse(null);
            if (job == null) return;
            job.state = "RUNNING"; job.attempts++;
        }
        // 처리(잠금 밖): 시세 조회는 오래 걸릴 수 있어서 잠금을 풀고 해요.
        try {
            var resolved = quotes.resolve(job.candidate, true);
            Instant finished = clock.instant();
            // 끝난 시각이 보강 가능 시간을 넘었거나 작업이 너무 오래됐으면 저장하지 않고 만료 처리해요.
            if (!eligible(finished) || finished.isAfter(job.queuedAt.plus(properties.maxAge()))) {
                synchronized (this) { job.state = "EXPIRED"; expired++; }
                return;
            }
            if (resolved.data() == null || !resolved.data().complete())
                throw new IllegalStateException(resolved.error() == null ? "INCOMPLETE_QUOTE" : resolved.error());
            // Append using actual completion time, never the original scan bucket.
            snapshots.saveEnriched(finished, job.candidate, resolved.data());
            synchronized (this) { job.state = "SUCCEEDED"; succeeded++; lastError = null; }
        } catch (RuntimeException error) {
            // 결과: 최대 시도 횟수에 닿았거나 입력값 오류(재시도해도 소용없는 오류)면 포기, 아니면 재시도 예약.
            // 재시도 간격은 retryDelay × 2^(시도횟수−1)로 점점 길어져요.
            synchronized (this) {
                lastError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                if (job.attempts >= properties.maxAttempts() || lastError.toUpperCase(Locale.ROOT).contains("ERROR INPUT FIELD")) {
                    job.state = "EXHAUSTED"; exhausted++;
                }
                else { job.state = "RETRY"; job.nextAttemptAt = clock.instant().plus(
                        properties.retryDelay().multipliedBy(1L << (job.attempts - 1))); }
            }
        } finally {
            synchronized (this) { lastProcessedAt = clock.instant(); }
        }
    }

    /**
     * 하는 일: 보강을 해도 되는 시각인지 확인해요. 장 시간 안이고, 종가 추천 피처 동결 시각 전이어야 해요.
     * 동결 이후 값은 그날 평가에 쓰이면 안 되기 때문으로 보여요.
     * 받는 것: 확인할 시각.
     * 돌려주는 것: 가능하면 true.
     */
    private boolean eligible(Instant at) {
        return session.evaluate(at).eligible() && MarketSessionHours.regularTime(at).isBefore(closing.featureFreezeAt());
    }
    /**
     * 하는 일: 접수 후 maxAge가 지난 작업을 대기열에서 지워요. 실행 중인 작업은 남겨 둬요.
     * 끝나지 않은 채 지워지는 작업은 만료 횟수에 더해요.
     * 받는 것: 현재 시각.
     * 돌려주는 것: 없음.
     */
    private void prune(Instant now) {
        jobs.values().removeIf(job -> {
            if (job.state.equals("RUNNING") || now.isBefore(job.queuedAt.plus(properties.maxAge()))) return false;
            if (!job.terminal()) expired++;
            return true;
        });
    }
    /**
     * 하는 일: 대기열의 현재 상태(상태별 작업 수, 누적 카운터, 마지막 처리 시각/오류)를 묶어 돌려줘요.
     * 받는 것: 없음.
     * 돌려주는 것: 대기열 스냅샷(그 순간의 상태 사본).
     */
    public synchronized Snapshot snapshot() {
        Map<String, Integer> states = new TreeMap<>();
        jobs.values().forEach(job -> states.merge(job.state, 1, Integer::sum));
        return new Snapshot(properties.enabled(), properties.capacity(), jobs.size(), Map.copyOf(states), accepted,
                coalesced, succeeded, exhausted, expired, rejected, lastProcessedAt, lastError);
    }
    /**
     * 진단 화면에 보여 줄 대기열 상태 묶음이에요.
     */
    public record Snapshot(boolean enabled, int capacity, int retainedJobs, Map<String, Integer> states,
            long accepted, long coalesced, long succeeded, long exhausted, long expired, long rejected,
            Instant lastProcessedAt, String lastError) {}
    /**
     * 보강 작업 하나예요. 상태는 QUEUED → RUNNING → SUCCEEDED / RETRY / EXHAUSTED / EXPIRED로 바뀌어요.
     * terminal()은 더 처리할 필요 없는 끝 상태(성공·소진·만료)인지 알려 줘요.
     */
    private static class Job {
        BroadCandidate candidate;
        final Instant queuedAt;
        Instant nextAttemptAt;
        int attempts;
        String state = "QUEUED";
        Job(BroadCandidate candidate, Instant at) { this.candidate = candidate; queuedAt = at; nextAttemptAt = at; }
        boolean terminal() { return Set.of("SUCCEEDED", "EXHAUSTED", "EXPIRED").contains(state); }
    }
}
