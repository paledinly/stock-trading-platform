/*
 * 장마감 평가 한 번(run)에서 뽑힌 추천 종목 한 줄을 나타내는 엔티티(DB 표 한 줄에 대응하는 자바 객체)예요.
 * 추천 시점의 점수·이유·위험·매수 기준가(가상 진입 기준)와 원천 후보(정밀 스캐너 감지 또는 시장 전체 Broad 스냅샷)를 함께 보관해요.
 * 장마감 추천 서비스가 만들고, 다음 날 성과·보유 판단·API 응답이 이 값을 읽어요. 실제 주문과는 관계없어요.
 */
package com.sunmo.stockplatform.closing.domain;

import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import com.sunmo.stockplatform.scanner.domain.ScannerType;
import com.sunmo.stockplatform.marketwide.domain.MarketBroadSnapshot;
import com.sunmo.stockplatform.stock.domain.Stock;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 장마감 추천 명단의 한 칸(순위, 종목, 점수, 이유, 매수 기준가)이에요. 같은 실행 안에서 종목은 한 번만 들어가요.
 * 신문 "오늘의 추천주" 표의 한 줄처럼, 발표 시점의 내용을 그대로 박제해 두고 나중에 성적을 매길 때 기준으로 써요.
 * 점수는 상승 확률이 아니라 정렬용 값이에요.
 */
@Entity
@Table(name = "closing_recommendation",
        uniqueConstraints = @UniqueConstraint(name = "uk_closing_recommendation_run_stock",
                columnNames = { "run_id", "stock_id" }))
public class ClosingRecommendation {
    /** 현재 전략 버전 표식이에요. v9.1 = 15:15 평가 기준(1515)을 뜻해요. */
    public static final String STRATEGY_VERSION = "closing-recommend-v9_1-1515";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private ClosingRecommendationRun run;

    /** 이 추천이 속한 실행을 돌려줘요. */
    public ClosingRecommendationRun getRun() { return run; }
    /** 하는 일: 이 추천을 실행(run)에 연결해요. 받는 것: 실행. 돌려주는 것: 없어요. */
    public void assignRun(ClosingRecommendationRun run) { this.run = run; }

    @Column(name = "recommendation_date", nullable = false)
    private LocalDate recommendationDate;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id")
    private Stock stock;

    // ⚠️ 확인 필요: 매핑은 optional = false인데 BROAD 생성자는 이 값을 비워 두고, DB(V18)도 NULL을 허용해요.
    // 현재는 Hibernate null 검사가 꺼져 있어 저장되는 것으로 보이지만, 설정이 바뀌면 BROAD 추천 저장이 실패할 수 있어요.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_detection_id")
    private ScannerDetection sourceDetection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "broad_snapshot_id")
    private MarketBroadSnapshot broadSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "candidate_source", nullable = false, length = 20)
    private ClosingCandidateSource candidateSource;

    @Column(name = "data_quality", nullable = false, length = 20)
    private String dataQuality;

    @Column(name = "coverage_minutes", nullable = false)
    private int coverageMinutes;

    @Column(name = "candidate_observed_at", nullable = false)
    private Instant candidateObservedAt;

    @Column(name = "missing_features", nullable = false, columnDefinition = "text")
    private String missingFeatures;

    // BROAD 추천은 스캐너 유형이 없어 null로 남아요(DB는 V18부터 NULL 허용, 매핑의 nullable = false와는 어긋나요).
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

    /** JPA가 DB에서 읽어 객체를 만들 때만 쓰는 빈 생성자예요. */
    protected ClosingRecommendation() {
    }

    /**
     * 하는 일: 정밀(PRECISION) 스캐너 감지 결과로 추천 한 줄을 만들어요. 가격·점수·거래대금 등은 감지 당시 값을 복사해요.
     * 받는 것: 추천 날짜, 생성 시각, 스캐너 감지, 순위, 추천 점수, 추천 이유, 위험 이유예요.
     * 돌려주는 것: CANDIDATE 상태의 추천 객체예요(데이터 품질 PRECISION_B, 관측 분 0으로 시작해요).
     */
    public ClosingRecommendation(LocalDate recommendationDate, Instant generatedAt, ScannerDetection sourceDetection,
            int rank, BigDecimal recommendationScore, String recommendationReason, String riskReason) {
        this.recommendationDate = recommendationDate;
        this.generatedAt = generatedAt;
        this.stock = sourceDetection.getStock();
        this.sourceDetection = sourceDetection;
        this.candidateSource = ClosingCandidateSource.PRECISION;
        this.dataQuality = "PRECISION_B";
        this.coverageMinutes = 0;
        this.candidateObservedAt = sourceDetection.getDetectedAt();
        this.missingFeatures = "[]";
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

    /**
     * 하는 일: 위 정밀 추천 생성자와 같지만 데이터 품질·관측 분 수·누락 특징값 목록을 직접 지정해요.
     * 받는 것: 위 생성자 입력 + 데이터 품질, 관측 분 수, 누락 특징값 JSON이에요.
     * 돌려주는 것: CANDIDATE 상태의 추천 객체예요.
     */
    public ClosingRecommendation(LocalDate recommendationDate, Instant generatedAt, ScannerDetection sourceDetection,
            int rank, BigDecimal recommendationScore, String recommendationReason, String riskReason,
            String dataQuality, int coverageMinutes, String missingFeatures) {
        this(recommendationDate, generatedAt, sourceDetection, rank, recommendationScore, recommendationReason, riskReason);
        this.dataQuality = dataQuality;
        this.coverageMinutes = coverageMinutes;
        this.missingFeatures = missingFeatures;
    }

    /**
     * 하는 일: 시장 전체 순위 수집(Broad) 스냅샷(특정 시각에 찍어 둔 상태 사진)으로 추천 한 줄을 만들어요.
     * 스캐너 감지·스캐너 유형·거래량 비율은 없어서 비워 두고, 순위 출처 정보를 featureSnapshot에 넣어요.
     * 받는 것: 추천 날짜, 생성 시각, Broad 스냅샷, 순위, 추천·기회·위험 점수, 추천·위험 이유, 누락 특징값 JSON이에요.
     * 돌려주는 것: CANDIDATE 상태의 BROAD 추천 객체예요.
     */
    public ClosingRecommendation(LocalDate recommendationDate, Instant generatedAt, MarketBroadSnapshot snapshot,
            int rank, BigDecimal recommendationScore, BigDecimal opportunityScore, BigDecimal riskScore,
            String recommendationReason, String riskReason, String missingFeatures) {
        this.recommendationDate = recommendationDate;
        this.generatedAt = generatedAt;
        this.stock = snapshot.getStock();
        this.broadSnapshot = snapshot;
        this.candidateSource = ClosingCandidateSource.BROAD;
        this.dataQuality = snapshot.getDataQuality().name();
        this.coverageMinutes = 0;
        this.candidateObservedAt = snapshot.getCapturedAt();
        this.missingFeatures = missingFeatures;
        this.rank = rank;
        this.recommendationScore = recommendationScore;
        this.buyReferencePrice = snapshot.getCurrentPrice();
        this.opportunityScore = opportunityScore;
        this.riskScore = riskScore;
        this.dailyTradingValue = snapshot.getAccumulatedTradingValue();
        this.fiveMinuteChangeRate = snapshot.getChangeRate();
        this.recommendationReason = recommendationReason;
        this.riskReason = riskReason;
        this.featureSnapshot = snapshot.getRankingSources();
        this.detectionReason = "BROAD_RANKING_SNAPSHOT";
        this.strategyVersion = STRATEGY_VERSION;
        this.status = ClosingRecommendationStatus.CANDIDATE;
    }

    /** 처음 저장 직전에 생성·수정 시각을 채워요. */
    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    /** 수정 저장 직전에 수정 시각을 갱신해요. */
    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    /** 아래는 저장된 값을 그대로 돌려주는 조회용 getter들이에요. */
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

    public MarketBroadSnapshot getBroadSnapshot() { return broadSnapshot; }
    public ClosingCandidateSource getCandidateSource() { return candidateSource; }
    public String getDataQuality() { return dataQuality; }
    public int getCoverageMinutes() { return coverageMinutes; }
    public Instant getCandidateObservedAt() { return candidateObservedAt; }
    public String getMissingFeatures() { return missingFeatures; }

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
