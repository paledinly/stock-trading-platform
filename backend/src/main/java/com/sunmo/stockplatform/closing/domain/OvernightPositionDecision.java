/*
 * 추천 종목을 다음 날 장중에 평가해 "보유/익절/경고/손절" 중 무엇인지 판단한 결과 한 건을 기록하는 엔티티(DB 표 한 줄에 대응하는 자바 객체)예요.
 * 판단 당시의 현재가·수익률·VWAP·체결강도·이동평균과 판단 이유(JSON)를 함께 남겨요. 한 추천에 여러 번 쌓일 수 있어요.
 * OvernightPositionDecisionService가 만들고, 보유 판단 API가 최신 판단을 읽어요. 실제 매매 지시가 아니에요.
 */
package com.sunmo.stockplatform.closing.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 판단 한 번의 기록이에요. 만들어진 뒤 바꾸는 메서드가 없는, 쌓이기만 하는 기록이에요.
 * 건강검진 기록지처럼 잰 시각과 수치(현재가, VWAP=거래량 가중 평균가, 체결강도, 5분봉 MA5/20/60)와 소견(판단 종류)을 한 장에 적어 둬요.
 */
@Entity
@Table(name = "overnight_position_decision")
public class OvernightPositionDecision {
    /** 판단 계산 방식 버전 표식이에요. */
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

    /** JPA가 DB에서 읽어 객체를 만들 때만 쓰는 빈 생성자예요. */
    protected OvernightPositionDecision() {
    }

    /**
     * 하는 일: 판단 한 건의 기록을 만들어요.
     * 받는 것: 추천, 평가시각, 현재가, 수익률, VWAP, VWAP 이격률, 체결강도, MA5/MA20/MA60, 목표·손절 도달 여부, 판단 종류, 판단 이유 JSON이에요.
     * 돌려주는 것: 저장 전 판단 기록 객체예요.
     */
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

    /** 처음 저장 직전에 생성 시각을 채워요. */
    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    /** 아래는 저장된 값을 그대로 돌려주는 조회용 getter들이에요. */
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
