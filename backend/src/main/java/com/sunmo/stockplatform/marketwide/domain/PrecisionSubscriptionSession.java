package com.sunmo.stockplatform.marketwide.domain;

import jakarta.persistence.*;
import java.time.*;

@Entity
@Table(name = "precision_subscription_session")
public class PrecisionSubscriptionSession {
    public enum Status { REQUESTED, ACTIVE, ENDED, REJECTED }
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "session_date", nullable = false) private LocalDate sessionDate;
    @Column(name = "stock_code", nullable = false, length = 12) private String stockCode;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt;
    @Column(name = "activated_at") private Instant activatedAt;
    @Column(name = "ended_at") private Instant endedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @Column(name = "end_reason", length = 40) private String endReason;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected PrecisionSubscriptionSession() {}
    public PrecisionSubscriptionSession(String stockCode, Instant requestedAt, boolean active) {
        this.stockCode = stockCode;
        this.requestedAt = requestedAt;
        this.sessionDate = requestedAt.atZone(MARKET_ZONE).toLocalDate();
        this.status = active ? Status.ACTIVE : Status.REQUESTED;
        this.activatedAt = active ? requestedAt : null;
    }
    public void activate(Instant at) { activatedAt = at; status = Status.ACTIVE; }
    public void reject(Instant at, String reason) { endedAt = at; endReason = trim(reason); status = Status.REJECTED; }
    public void end(Instant at, String reason) { endedAt = at; endReason = trim(reason); status = Status.ENDED; }
    private String trim(String value) { return value == null ? null : value.substring(0, Math.min(40, value.length())); }
    @PrePersist void prePersist() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }
    public Long getId() { return id; }
    public LocalDate getSessionDate() { return sessionDate; }
    public String getStockCode() { return stockCode; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getActivatedAt() { return activatedAt; }
    public Instant getEndedAt() { return endedAt; }
    public Status getStatus() { return status; }
    public String getEndReason() { return endReason; }
}
