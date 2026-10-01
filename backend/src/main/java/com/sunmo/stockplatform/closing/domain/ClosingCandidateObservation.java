package com.sunmo.stockplatform.closing.domain;

import com.sunmo.stockplatform.stock.domain.Stock;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "closing_candidate_observation",
        uniqueConstraints = @UniqueConstraint(name = "uk_closing_candidate_observation",
                columnNames = { "run_id", "stock_id", "candidate_source" }))
public class ClosingCandidateObservation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "run_id")
    private ClosingRecommendationRun run;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "stock_id") private Stock stock;
    @Column(name = "candidate_source", nullable = false, length = 20) private String candidateSource;
    @Column(nullable = false, length = 20) private String disposition;
    @Column(name = "decision_reason", nullable = false, length = 80) private String decisionReason;
    @Column(name = "signal_observed_at", nullable = false) private Instant signalObservedAt;
    @Column(name = "signal_price", precision = 20, scale = 4) private BigDecimal signalPrice;
    @Column(name = "final_score", precision = 8, scale = 3) private BigDecimal finalScore;
    @Column(name = "expected_session_date", nullable = false) private LocalDate expectedSessionDate;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private OvernightPerformanceStatus status;
    @Column(name = "entry_at") private Instant entryAt;
    @Column(name = "entry_price", precision = 20, scale = 4) private BigDecimal entryPrice;
    @Column(name = "exit_at") private Instant exitAt;
    @Column(name = "exit_price", precision = 20, scale = 4) private BigDecimal exitPrice;
    @Column(name = "gross_return_rate", precision = 12, scale = 6) private BigDecimal grossReturnRate;
    @Column(name = "net_return_rate", precision = 12, scale = 6) private BigDecimal netReturnRate;
    @Column(name = "exit_reason", length = 40) private String exitReason;
    @Column(name = "execution_ambiguous", nullable = false) private boolean executionAmbiguous;
    @Column(name = "target_rate", nullable = false, precision = 12, scale = 6) private BigDecimal targetRate;
    @Column(name = "stop_rate", nullable = false, precision = 12, scale = 6) private BigDecimal stopRate;
    @Column(name = "missing_intervals", nullable = false, columnDefinition = "text") private String missingIntervals = "[]";
    @Column(name = "cost_assumption", nullable = false, columnDefinition = "text") private String costAssumption = "{}";
    @Column(name = "cost_status", nullable = false, length = 40) private String costStatus = "NOT_EVALUATED";
    @Column(name = "evaluated_at", nullable = false) private Instant evaluatedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ClosingCandidateObservation() { }

    public ClosingCandidateObservation(ClosingRecommendationRun run, Stock stock, String source,
            String disposition, String reason, Instant observedAt, BigDecimal signalPrice,
            BigDecimal finalScore, LocalDate sessionDate, BigDecimal target, BigDecimal stop) {
        this.run = run;
        this.stock = stock;
        this.candidateSource = source;
        this.disposition = disposition;
        this.decisionReason = reason;
        this.signalObservedAt = observedAt;
        this.signalPrice = signalPrice;
        this.finalScore = finalScore;
        this.expectedSessionDate = sessionDate;
        this.targetRate = target;
        this.stopRate = stop;
        this.status = OvernightPerformanceStatus.PENDING;
        this.evaluatedAt = Instant.now();
    }

    public void verifyThresholds(BigDecimal target, BigDecimal stop) {
        if (targetRate.compareTo(target) != 0 || stopRate.compareTo(stop) != 0)
            throw new IllegalStateException("Candidate observation thresholds are fixed for this run");
    }

    public void observe(Instant at, OvernightPerformanceStatus status, String missing,
            Instant entryAt, BigDecimal entryPrice,
            OvernightExecutionResult result, String assumption) {
        this.evaluatedAt = at;
        this.status = status;
        this.missingIntervals = missing;
        this.entryAt = entryAt;
        this.entryPrice = entryPrice;
        this.costAssumption = assumption;
        if (result == null) {
            this.costStatus = entryPrice == null ? "ENTRY_PRICE_UNAVAILABLE" : "EXIT_DATA_UNAVAILABLE";
            return;
        }
        this.exitAt = result.exitAt();
        this.exitPrice = result.exitPrice();
        this.grossReturnRate = result.grossReturnRate();
        this.netReturnRate = result.netReturnRate();
        this.exitReason = result.exitReason();
        this.executionAmbiguous = result.ambiguous();
        this.costStatus = result.costsApplied() ? "COSTS_APPLIED" : "ZERO_COSTS_UNVERIFIED";
    }

    @PrePersist void prePersist() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }

    public record OvernightExecutionResult(Instant exitAt, BigDecimal exitPrice, BigDecimal grossReturnRate,
            BigDecimal netReturnRate, String exitReason, boolean ambiguous, boolean costsApplied) { }
    public Long getId() { return id; }
    public ClosingRecommendationRun getRun() { return run; }
    public Stock getStock() { return stock; }
    public String getCandidateSource() { return candidateSource; }
    public String getDisposition() { return disposition; }
    public String getDecisionReason() { return decisionReason; }
    public Instant getSignalObservedAt() { return signalObservedAt; }
    public BigDecimal getSignalPrice() { return signalPrice; }
    public BigDecimal getFinalScore() { return finalScore; }
    public LocalDate getExpectedSessionDate() { return expectedSessionDate; }
    public OvernightPerformanceStatus getStatus() { return status; }
    public Instant getEntryAt() { return entryAt; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public Instant getExitAt() { return exitAt; }
    public BigDecimal getExitPrice() { return exitPrice; }
    public BigDecimal getGrossReturnRate() { return grossReturnRate; }
    public BigDecimal getNetReturnRate() { return netReturnRate; }
    public String getExitReason() { return exitReason; }
    public boolean isExecutionAmbiguous() { return executionAmbiguous; }
    public BigDecimal getTargetRate() { return targetRate; }
    public BigDecimal getStopRate() { return stopRate; }
    public String getMissingIntervals() { return missingIntervals; }
    public String getCostAssumption() { return costAssumption; }
    public String getCostStatus() { return costStatus; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
}
