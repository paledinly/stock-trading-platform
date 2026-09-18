package com.sunmo.stockplatform.closing.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "overnight_performance",
        uniqueConstraints = @UniqueConstraint(name = "uk_overnight_performance_recommendation",
                columnNames = "closing_recommendation_id"))
public class OvernightPerformance {
    public static final String VERSION = "overnight-performance-v1";
    public static final String OBSERVATION_VERSION = "overnight-observation-v2";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "closing_recommendation_id")
    private ClosingRecommendation recommendation;

    @Column(name = "next_trading_date")
    private LocalDate nextTradingDate;

    @Column(name = "expected_session_date")
    private LocalDate expectedSessionDate;
    @Column(name = "session_open")
    private Instant sessionOpen;
    @Column(name = "session_close")
    private Instant sessionClose;
    @Column(name = "observed_through")
    private Instant observedThrough;
    @Column(name = "missing_intervals", nullable = false, columnDefinition = "text")
    private String missingIntervals = "[]";
    @Column(name = "latest_price", precision = 20, scale = 4)
    private BigDecimal latestPrice;
    @Column(name = "latest_return_rate", precision = 12, scale = 6)
    private BigDecimal latestReturnRate;
    @Column(name = "target_rate", precision = 12, scale = 6)
    private BigDecimal targetRate;
    @Column(name = "stop_rate", precision = 12, scale = 6)
    private BigDecimal stopRate;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    @Column(name = "open_price", precision = 20, scale = 4)
    private BigDecimal openPrice;

    @Column(name = "high_price", precision = 20, scale = 4)
    private BigDecimal highPrice;

    @Column(name = "low_price", precision = 20, scale = 4)
    private BigDecimal lowPrice;

    @Column(name = "close_price", precision = 20, scale = 4)
    private BigDecimal closePrice;

    @Column(name = "open_return_rate", precision = 12, scale = 6)
    private BigDecimal openReturnRate;

    @Column(name = "close_return_rate", precision = 12, scale = 6)
    private BigDecimal closeReturnRate;

    @Column(name = "max_return_rate", precision = 12, scale = 6)
    private BigDecimal maxReturnRate;

    @Column(name = "max_drawdown_rate", precision = 12, scale = 6)
    private BigDecimal maxDrawdownRate;

    @Column(name = "target_hit", nullable = false)
    private boolean targetHit;

    @Column(name = "stop_hit", nullable = false)
    private boolean stopHit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OvernightPerformanceStatus status;

    @Column(name = "calculation_version", nullable = false, length = 40)
    private String calculationVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OvernightPerformance() {
    }

    public OvernightPerformance(ClosingRecommendation recommendation) {
        this.recommendation = recommendation;
        this.status = OvernightPerformanceStatus.PENDING;
        this.calculationVersion = VERSION;
        this.evaluatedAt = Instant.now();
    }

    public void complete(LocalDate nextTradingDate, BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice,
            BigDecimal closePrice, BigDecimal openReturnRate, BigDecimal closeReturnRate, BigDecimal maxReturnRate,
            BigDecimal maxDrawdownRate, boolean targetHit, boolean stopHit) {
        this.nextTradingDate = nextTradingDate;
        this.openPrice = openPrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.closePrice = closePrice;
        this.openReturnRate = openReturnRate;
        this.closeReturnRate = closeReturnRate;
        this.maxReturnRate = maxReturnRate;
        this.maxDrawdownRate = maxDrawdownRate;
        this.targetHit = targetHit;
        this.stopHit = stopHit;
        this.status = OvernightPerformanceStatus.COMPLETED;
        this.calculationVersion = VERSION;
        this.evaluatedAt = Instant.now();
    }

    public void markDataMissing() {
        this.status = OvernightPerformanceStatus.DATA_MISSING;
        this.calculationVersion = VERSION;
        this.evaluatedAt = Instant.now();
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public ClosingRecommendation getRecommendation() {
        return recommendation;
    }

    public LocalDate getNextTradingDate() {
        return nextTradingDate;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public BigDecimal getOpenPrice() {
        return openPrice;
    }

    public BigDecimal getHighPrice() {
        return highPrice;
    }

    public BigDecimal getLowPrice() {
        return lowPrice;
    }

    public BigDecimal getClosePrice() {
        return closePrice;
    }

    public BigDecimal getOpenReturnRate() {
        return openReturnRate;
    }

    public BigDecimal getCloseReturnRate() {
        return closeReturnRate;
    }

    public BigDecimal getMaxReturnRate() {
        return maxReturnRate;
    }

    public BigDecimal getMaxDrawdownRate() {
        return maxDrawdownRate;
    }

    public boolean isTargetHit() {
        return targetHit;
    }

    public boolean isStopHit() {
        return stopHit;
    }

    public OvernightPerformanceStatus getStatus() {
        return status;
    }

    public String getCalculationVersion() {
        return calculationVersion;
    }

    public void initializeObservation(LocalDate date, Instant open, Instant close, BigDecimal target, BigDecimal stop) {
        expectedSessionDate = date;
        nextTradingDate = date;
        sessionOpen = open;
        sessionClose = close;
        targetRate = target;
        stopRate = stop;
        calculationVersion = OBSERVATION_VERSION;
    }

    public void observe(Instant at, Instant through, String missing, OvernightPerformanceStatus state,
            BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal latest,
            BigDecimal openReturn, BigDecimal latestReturn, BigDecimal maximum, BigDecimal minimum,
            boolean target, boolean stop) {
        evaluatedAt = at;
        observedThrough = through;
        missingIntervals = missing;
        status = state;
        openPrice = open;
        highPrice = high;
        lowPrice = low;
        latestPrice = latest;
        latestReturnRate = latestReturn;
        openReturnRate = openReturn;
        maxReturnRate = maximum;
        maxDrawdownRate = minimum;
        targetHit = target;
        stopHit = stop;
        closePrice = state == OvernightPerformanceStatus.COMPLETED ? latest : null;
        closeReturnRate = state == OvernightPerformanceStatus.COMPLETED ? latestReturn : null;
    }

    public LocalDate getExpectedSessionDate() { return expectedSessionDate; }
    public Instant getSessionOpen() { return sessionOpen; }
    public Instant getSessionClose() { return sessionClose; }
    public Instant getObservedThrough() { return observedThrough; }
    public String getMissingIntervals() { return missingIntervals; }
    public BigDecimal getLatestPrice() { return latestPrice; }
    public BigDecimal getLatestReturnRate() { return latestReturnRate; }
    public BigDecimal getTargetRate() { return targetRate; }
    public BigDecimal getStopRate() { return stopRate; }
}
