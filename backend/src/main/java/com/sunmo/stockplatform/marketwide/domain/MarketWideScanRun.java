package com.sunmo.stockplatform.marketwide.domain;

import jakarta.persistence.*;
import java.time.*;

@Entity
@Table(name = "market_wide_scan_run", uniqueConstraints = @UniqueConstraint(
        name = "uk_market_wide_scan_run_schedule", columnNames = "scheduled_for"))
public class MarketWideScanRun {
    public enum Status { RUNNING, COMPLETED, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;
    @Column(name = "scheduled_for", nullable = false)
    private Instant scheduledFor;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Status status;
    @Column(name = "scanned_count", nullable = false)
    private int scannedCount;
    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;
    @Column(nullable = false)
    private boolean fallback;
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MarketWideScanRun() {}

    public MarketWideScanRun(LocalDate date, Instant scheduledFor, Instant startedAt) {
        this.sessionDate = date;
        this.scheduledFor = scheduledFor;
        this.startedAt = startedAt;
        this.status = Status.RUNNING;
    }

    public void complete(Instant at, int scanned, int candidates, boolean fallback) {
        completedAt = at;
        scannedCount = scanned;
        candidateCount = candidates;
        this.fallback = fallback;
        status = Status.COMPLETED;
        errorMessage = null;
    }

    public void fail(Instant at, String error) {
        completedAt = at;
        status = Status.FAILED;
        errorMessage = error;
    }

    public void restart(Instant at) {
        startedAt = at;
        completedAt = null;
        status = Status.RUNNING;
        errorMessage = null;
    }

    @PrePersist void prePersist() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }
    public Status getStatus() { return status; }
    public Instant getScheduledFor() { return scheduledFor; }
}
