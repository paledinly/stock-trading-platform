package com.sunmo.stockplatform.closing.domain;

import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import com.sunmo.stockplatform.scanner.domain.ScannerType;
import com.sunmo.stockplatform.stock.domain.Stock;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "closing_recommendation",
        uniqueConstraints = @UniqueConstraint(name = "uk_closing_recommendation_date_stock",
                columnNames = { "recommendation_date", "stock_id" }))
public class ClosingRecommendation {
    public static final String STRATEGY_VERSION = "closing-recommendation-v2-ma";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recommendation_date", nullable = false)
    private LocalDate recommendationDate;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id")
    private Stock stock;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_detection_id")
    private ScannerDetection sourceDetection;

    @Enumerated(EnumType.STRING)
    @Column(name = "scanner_type", nullable = false, length = 30)
    private ScannerType scannerType;

    @Column(name = "rank_no", nullable = false)
    private int rank;

    @Column(name = "recommendation_score", nullable = false, precision = 8, scale = 3)
    private BigDecimal recommendationScore;

    @Column(name = "buy_reference_price", nullable = false, precision = 20, scale = 4)
    private BigDecimal buyReferencePrice;

    @Column(name = "opportunity_score", precision = 6, scale = 3)
    private BigDecimal opportunityScore;

    @Column(name = "risk_score", precision = 6, scale = 3)
    private BigDecimal riskScore;

    @Column(name = "daily_trading_value", precision = 20, scale = 4)
    private BigDecimal dailyTradingValue;

    @Column(name = "five_minute_change_rate", precision = 12, scale = 6)
    private BigDecimal fiveMinuteChangeRate;

    @Column(name = "volume_ratio", precision = 12, scale = 6)
    private BigDecimal volumeRatio;

    @Column(name = "recommendation_reason", nullable = false, columnDefinition = "text")
    private String recommendationReason;

    @Column(name = "risk_reason", nullable = false, columnDefinition = "text")
    private String riskReason;

    @Column(name = "feature_snapshot", columnDefinition = "text")
    private String featureSnapshot;

    @Column(name = "detection_reason", columnDefinition = "text")
    private String detectionReason;

    @Column(name = "strategy_version", nullable = false, length = 40)
    private String strategyVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ClosingRecommendationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ClosingRecommendation() {
    }

    public ClosingRecommendation(LocalDate recommendationDate, Instant generatedAt, ScannerDetection sourceDetection,
            int rank, BigDecimal recommendationScore, String recommendationReason, String riskReason) {
        this.recommendationDate = recommendationDate;
        this.generatedAt = generatedAt;
        this.stock = sourceDetection.getStock();
        this.sourceDetection = sourceDetection;
        this.scannerType = sourceDetection.getType();
        this.rank = rank;
        this.recommendationScore = recommendationScore;
        this.buyReferencePrice = sourceDetection.getDetectedPrice();
        this.opportunityScore = sourceDetection.getOpportunityScore();
        this.riskScore = sourceDetection.getRiskScore();
        this.dailyTradingValue = sourceDetection.getDailyValue();
        this.fiveMinuteChangeRate = sourceDetection.getChangeRate();
        this.volumeRatio = sourceDetection.getVolumeRatio();
        this.recommendationReason = recommendationReason;
        this.riskReason = riskReason;
        this.featureSnapshot = sourceDetection.getFeatureSnapshot();
        this.detectionReason = sourceDetection.getDetectionReason();
        this.strategyVersion = STRATEGY_VERSION;
        this.status = ClosingRecommendationStatus.CANDIDATE;
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

    public LocalDate getRecommendationDate() {
        return recommendationDate;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public Stock getStock() {
        return stock;
    }

    public ScannerDetection getSourceDetection() {
        return sourceDetection;
    }

    public ScannerType getScannerType() {
        return scannerType;
    }

    public int getRank() {
        return rank;
    }

    public BigDecimal getRecommendationScore() {
        return recommendationScore;
    }

    public BigDecimal getBuyReferencePrice() {
        return buyReferencePrice;
    }

    public BigDecimal getOpportunityScore() {
        return opportunityScore;
    }

    public BigDecimal getRiskScore() {
        return riskScore;
    }

    public BigDecimal getDailyTradingValue() {
        return dailyTradingValue;
    }

    public BigDecimal getFiveMinuteChangeRate() {
        return fiveMinuteChangeRate;
    }

    public BigDecimal getVolumeRatio() {
        return volumeRatio;
    }

    public String getRecommendationReason() {
        return recommendationReason;
    }

    public String getRiskReason() {
        return riskReason;
    }

    public String getFeatureSnapshot() {
        return featureSnapshot;
    }

    public String getDetectionReason() {
        return detectionReason;
    }

    public String getStrategyVersion() {
        return strategyVersion;
    }

    public ClosingRecommendationStatus getStatus() {
        return status;
    }
}
