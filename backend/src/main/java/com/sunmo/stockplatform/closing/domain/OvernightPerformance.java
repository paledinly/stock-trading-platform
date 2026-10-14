/*
 * 장마감 추천 종목 하나의 "다음 거래일 성과"를 기록하는 엔티티(DB 표 한 줄에 대응하는 자바 객체)예요.
 * 다음 날 시가·고가·저가·종가 기준 수익률, 목표/손절 도달 여부, 15:20 이전 가상 진입가 기준 가상 청산 결과(비용 전/후)를 담아요.
 * OvernightPerformanceService가 갱신하고, 성과 API·전략 분석·시장 커버리지가 읽어요. 실제 계좌 손익이 아니에요.
 */
package com.sunmo.stockplatform.closing.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 추천 한 건당 하나만 있는 다음 날 성적표예요(추천 ID에 유일 제약).
 * 학생 성적표처럼 처음엔 빈칸(PENDING)으로 만들어 두고, 다음 날 장이 진행되며 칸을 채우다가 마감 뒤 확정(COMPLETED)해요.
 * 수익률 값은 퍼센트 단위예요(2는 2%).
 */
@Entity
@Table(name = "overnight_performance",
        uniqueConstraints = @UniqueConstraint(name = "uk_overnight_performance_recommendation",
                columnNames = "closing_recommendation_id"))
public class OvernightPerformance {
    /** 예전(v1) 계산 방식 표식이에요. 생성 직후 기본값으로 들어가요. */
    public static final String VERSION = "overnight-performance-v1";
    /** 현재 관측 방식(v2) 표식이에요. 전략 분석은 이 버전의 완료 성과만 집계해요. */
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
    @Column(name = "virtual_entry_at")
    private Instant virtualEntryAt;
    @Column(name = "virtual_entry_price", precision = 20, scale = 4)
    private BigDecimal virtualEntryPrice;
    @Column(name = "execution_exit_at")
    private Instant executionExitAt;
    @Column(name = "execution_exit_price", precision = 20, scale = 4)
    private BigDecimal executionExitPrice;
    @Column(name = "gross_return_rate", precision = 12, scale = 6)
    private BigDecimal grossReturnRate;
    @Column(name = "net_return_rate", precision = 12, scale = 6)
    private BigDecimal netReturnRate;
    @Column(name = "execution_exit_reason", length = 40)
    private String executionExitReason;
    @Column(name = "execution_ambiguous", nullable = false)
    private boolean executionAmbiguous;
    @Column(name = "execution_model_version", length = 40)
    private String executionModelVersion;
    @Column(name = "cost_assumption", nullable = false, columnDefinition = "text")
    private String costAssumption = "{}";
    @Column(name = "cost_status", nullable = false, length = 40)
    private String costStatus = "NOT_EVALUATED";

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

    /** JPA가 DB에서 읽어 객체를 만들 때만 쓰는 빈 생성자예요. */
    protected OvernightPerformance() {
    }

    /**
     * 하는 일: 추천 한 건에 대한 빈 성적표를 PENDING 상태로 만들어요.
     * 받는 것: 추천이에요. 돌려주는 것: 성과 객체예요.
     */
    public OvernightPerformance(ClosingRecommendation recommendation) {
        this.recommendation = recommendation;
        this.status = OvernightPerformanceStatus.PENDING;
        this.calculationVersion = VERSION;
        this.evaluatedAt = Instant.now();
    }

    /**
     * 하는 일: 다음 날 시가·고가·저가·종가와 수익률, 목표/손절 도달 여부를 한 번에 채우고 COMPLETED(v1)로 확정해요.
     * 현재 main 코드에서는 호출하는 곳이 없는 이전(v1) 방식으로 보여요. 지금은 initializeObservation/observe를 써요.
     * 받는 것: 다음 거래일, 시·고·저·종가, 시가·종가 수익률, 최대 상승·최대 하락률, 목표·손절 도달 여부예요. 돌려주는 것: 없어요.
     */
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

    /**
     * 하는 일: 관측(v2) 방식으로 전환하며 대상 거래일, 장 시작·마감 시각, 목표/손절 비율을 정해 둬요.
     * 받는 것: 다음 거래일, 장 시작 시각, 장 마감 시각, 목표 비율, 손절 비율이에요. 돌려주는 것: 없어요.
     */
    public void initializeObservation(LocalDate date, Instant open, Instant close, BigDecimal target, BigDecimal stop) {
        expectedSessionDate = date;
        nextTradingDate = date;
        sessionOpen = open;
        sessionClose = close;
        targetRate = target;
        stopRate = stop;
        calculationVersion = OBSERVATION_VERSION;
    }

    /**
     * 하는 일: 지금까지 관측한 다음 날 봉으로 성과 칸을 갱신해요. 장 중에는 latest 값만 바뀌고,
     * 상태가 COMPLETED일 때만 latest를 종가·종가 수익률로 확정해요(미완료면 종가는 비워 둬요).
     * 받는 것: 평가시각, 관측 끝 시각, 결측 구간 JSON, 상태, 시·고·저·최근가, 시가·최근 수익률, 최대 상승·최대 하락률, 목표·손절 도달 여부예요.
     * 돌려주는 것: 없어요.
     */
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

    /**
     * 하는 일: 가상 진입(15:20 이전)과 다음 날 가상 청산 결과를 기록해요. 실제 체결이 아니에요.
     * 진입가가 없거나 청산 결과가 없으면 이유만 costStatus에 남기고 끝내요.
     * 받는 것: 가상 진입 시각·가격, 시뮬레이터 청산 결과(없을 수 있음), 비용 가정 JSON이에요. 돌려주는 것: 없어요.
     */
    public void recordExecution(Instant entryAt, BigDecimal entryPrice,
            com.sunmo.stockplatform.closing.application.OvernightExecutionSimulator.ExecutionResult execution,
            String assumption) {
        virtualEntryAt = entryAt;
        virtualEntryPrice = entryPrice;
        costAssumption = assumption;
        if (entryAt == null || entryPrice == null) {
            costStatus = "ENTRY_PRICE_UNAVAILABLE";
            return;
        }
        if (execution == null) {
            costStatus = "EXIT_DATA_UNAVAILABLE";
            return;
        }
        executionExitAt = execution.exitAt();
        executionExitPrice = execution.exitReferencePrice();
        grossReturnRate = execution.grossReturnRate();
        // 비용이 설정되지 않았으면 비용 차감 수익률은 비워 두고 "비용 미검증"으로 표시해요.
        netReturnRate = execution.costsApplied() ? execution.netReturnRate() : null;
        executionExitReason = execution.exitReason();
        executionAmbiguous = execution.ambiguous();
        executionModelVersion = execution.modelVersion();
        costStatus = execution.costsApplied() ? "COSTS_APPLIED" : "ZERO_COSTS_UNVERIFIED";
    }

    /** 관측(v2) 방식에서 추가된 값들의 조회용 getter들이에요. */
    public LocalDate getExpectedSessionDate() { return expectedSessionDate; }
    public Instant getSessionOpen() { return sessionOpen; }
    public Instant getSessionClose() { return sessionClose; }
    public Instant getObservedThrough() { return observedThrough; }
    public String getMissingIntervals() { return missingIntervals; }
    public BigDecimal getLatestPrice() { return latestPrice; }
    public BigDecimal getLatestReturnRate() { return latestReturnRate; }
    public BigDecimal getTargetRate() { return targetRate; }
    public BigDecimal getStopRate() { return stopRate; }
    public Instant getVirtualEntryAt() { return virtualEntryAt; }
    public BigDecimal getVirtualEntryPrice() { return virtualEntryPrice; }
    public Instant getExecutionExitAt() { return executionExitAt; }
    public BigDecimal getExecutionExitPrice() { return executionExitPrice; }
    public BigDecimal getGrossReturnRate() { return grossReturnRate; }
    public BigDecimal getNetReturnRate() { return netReturnRate; }
    public String getExecutionExitReason() { return executionExitReason; }
    public boolean isExecutionAmbiguous() { return executionAmbiguous; }
    public String getExecutionModelVersion() { return executionModelVersion; }
    public String getCostAssumption() { return costAssumption; }
    public String getCostStatus() { return costStatus; }
}
