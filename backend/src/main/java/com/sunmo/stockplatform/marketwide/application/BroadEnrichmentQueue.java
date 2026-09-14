package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.market.config.BroadEnrichmentProperties;
import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.marketwide.domain.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** Bounded, single-process queue. No historical snapshot is rewritten by enrichment. */
@Component
public class BroadEnrichmentQueue {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final BroadQuoteResolver quotes;
    private final BroadSnapshotService snapshots;
    private final BroadEnrichmentProperties properties;
    private final MarketSessionPolicy session;
    private final ClosingRecommendationProperties closing;
    private final Clock clock;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private long accepted, coalesced, succeeded, exhausted, expired, rejected;
    private Instant lastProcessedAt;
    private String lastError;

    @Autowired
    public BroadEnrichmentQueue(BroadQuoteResolver quotes, BroadSnapshotService snapshots,
            BroadEnrichmentProperties properties, MarketSessionPolicy session, ClosingRecommendationProperties closing) {
        this(quotes, snapshots, properties, session, closing, Clock.systemUTC());
    }
    public BroadEnrichmentQueue(BroadQuoteResolver quotes, BroadSnapshotService snapshots,
            BroadEnrichmentProperties properties, MarketSessionPolicy session, ClosingRecommendationProperties closing, Clock clock) {
        this.quotes = quotes; this.snapshots = snapshots; this.properties = properties;
        this.session = session; this.closing = closing; this.clock = clock;
    }

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
                if (!existing.terminal() && !existing.state.equals("RUNNING")) existing.candidate = capture.candidate();
                coalesced++;
                continue;
            }
            if (jobs.size() >= properties.capacity()) { rejected++; continue; }
            jobs.put(code, new Job(capture.candidate(), now));
            accepted++;
        }
    }

    @Scheduled(fixedDelayString = "${market.wide.enrichment.poll-interval:1s}", scheduler = "broadEnrichmentScheduler")
    public void processOne() {
        Job job;
        Instant now = clock.instant();
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
        try {
            var resolved = quotes.resolve(job.candidate, true);
            Instant finished = clock.instant();
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

    private boolean eligible(Instant at) {
        return session.evaluate(at).eligible() && at.atZone(SEOUL).toLocalTime().isBefore(closing.featureFreezeAt());
    }
    private void prune(Instant now) {
        jobs.values().removeIf(job -> {
            if (job.state.equals("RUNNING") || now.isBefore(job.queuedAt.plus(properties.maxAge()))) return false;
            if (!job.terminal()) expired++;
            return true;
        });
    }
    public synchronized Snapshot snapshot() {
        Map<String, Integer> states = new TreeMap<>();
        jobs.values().forEach(job -> states.merge(job.state, 1, Integer::sum));
        return new Snapshot(properties.enabled(), properties.capacity(), jobs.size(), Map.copyOf(states), accepted,
                coalesced, succeeded, exhausted, expired, rejected, lastProcessedAt, lastError);
    }
    public record Snapshot(boolean enabled, int capacity, int retainedJobs, Map<String, Integer> states,
            long accepted, long coalesced, long succeeded, long exhausted, long expired, long rejected,
            Instant lastProcessedAt, String lastError) {}
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
