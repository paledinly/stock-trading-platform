package com.sunmo.stockplatform.closing.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "overnight_position_decision")
public class OvernightPositionDecision {
    public static final String VERSION = "overnight-decision-v1";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "closing_recommendation_id")
    private ClosingRecommendation recommendation;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    @Column(name = "current_price", precision = 20, scale = 4)
    private BigDecimal currentPrice;

    @Column(name = "return_rate", precision = 12, scale = 6)
    private BigDecimal returnRate;

    @Column(name = "vwap", precision = 20, scale = 6)
    private BigDecimal vwap;

    @Column(name = "vwap_distance_rate", precision = 12, scale = 6)
    private BigDecimal vwapDistanceRate;

    @Column(name = "trade_strength", precision = 12, scale = 6)
    private BigDecimal tradeStrength;

    @Column(name = "ma5", precision = 20, scale = 6)
    private BigDecimal ma5;

    @Column(name = "ma20", precision = 20, scale = 6)
    private BigDecimal ma20;

    @Column(name = "ma60", precision = 20, scale = 6)
    private BigDecimal ma60;

    @Column(name = "target_hit", nullable = false)
    private boolean targetHit;

    @Column(name = "stop_hit", nullable = false)
    private boolean stopHit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OvernightPositionDecisionType decision;

    @Column(name = "reason_json", nullable = false, columnDefinition = "text")
    private String reasonJson;

    @Column(name = "calculation_version", nullable = false, length = 40)
    private String calculationVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OvernightPositionDecision() {
    }

    public OvernightPositionDecision(ClosingRecommendation recommendation, Instant evaluatedAt,
            BigDecimal currentPrice, BigDecimal returnRate, BigDecimal vwap, BigDecimal vwapDistanceRate,
            BigDecimal tradeStrength, BigDecimal ma5, BigDecimal ma20, BigDecimal ma60, boolean targetHit,
            boolean stopHit, OvernightPositionDecisionType decision, String reasonJson) {
        this.recommendation = recommendation;
        this.evaluatedAt = evaluatedAt;
        this.currentPrice = currentPrice;
        this.returnRate = returnRate;
        this.vwap = vwap;
        this.vwapDistanceRate = vwapDistanceRate;
        this.tradeStrength = tradeStrength;
        this.ma5 = ma5;
        this.ma20 = ma20;
        this.ma60 = ma60;
        this.targetHit = targetHit;
        this.stopHit = stopHit;
        this.decision = decision;
        this.reasonJson = reasonJson;
        this.calculationVersion = VERSION;
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public ClosingRecommendation getRecommendation() {
        return recommendation;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    public BigDecimal getReturnRate() {
        return returnRate;
    }

    public BigDecimal getVwap() {
        return vwap;
    }

    public BigDecimal getVwapDistanceRate() {
        return vwapDistanceRate;
    }

    public BigDecimal getTradeStrength() {
        return tradeStrength;
    }

    public BigDecimal getMa5() {
        return ma5;
    }

    public BigDecimal getMa20() {
        return ma20;
    }

    public BigDecimal getMa60() {
        return ma60;
    }

    public boolean isTargetHit() {
        return targetHit;
    }

    public boolean isStopHit() {
        return stopHit;
    }

    public OvernightPositionDecisionType getDecision() {
        return decision;
    }

    public String getReasonJson() {
        return reasonJson;
    }

    public String getCalculationVersion() {
        return calculationVersion;
    }
}
