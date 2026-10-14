/*
 * 장마감 평가에서 SELECTED/WATCH 등으로 분류된 후보 하나를 "관찰 기록"으로 남기는 엔티티(DB 표 한 줄에 대응하는 자바 객체)예요.
 * 추천 명단에 들지 않았어도 같은 규칙으로 가상 진입·다음 거래일 목표/손절 결과를 계산해 저장해, 나중에 가설 검증에 써요.
 * ClosingCandidateObservationService가 만들고 갱신하며, ClosingCandidateObservationRepository로 저장돼요.
 */
package com.sunmo.stockplatform.closing.domain;

import com.sunmo.stockplatform.stock.domain.Stock;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

/**
 * 한 실행(run)에서 한 종목·한 후보 출처(PRECISION/BROAD) 조합에 대한 관찰 기록이에요(조합당 한 줄만 허용).
 * 체육대회 예선에서 본선에 못 간 선수도 기록지를 따로 남겨 두고, 다음 날 실제 기록을 적어 비교하는 것과 같아요.
 * 신호 시각·가격·점수와 목표/손절 비율은 처음 만들 때 고정되고, 진입·청산 결과는 재평가 때마다 덮어써요.
 */
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

    /** JPA가 DB에서 읽어 객체를 만들 때만 쓰는 빈 생성자예요. */
    protected ClosingCandidateObservation() { }

    /**
     * 하는 일: 새 관찰 기록을 PENDING(아직 다음 날 결과 없음) 상태로 만들어요.
     * 받는 것: 실행, 종목, 후보 출처, 분류(disposition), 분류 이유, 신호 관측시각, 신호 가격, 최종 점수,
     * 결과를 볼 다음 거래일, 목표 비율, 손절 비율이에요.
     * 돌려주는 것: 저장 전 관찰 기록 객체예요.
     */
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

    /**
     * 하는 일: 이번 재평가의 목표/손절 비율이 처음 저장한 값과 같은지 확인해요.
     * 같은 실행 안에서 기준을 바꿔 결과를 다시 계산하면 비교가 무의미해지기 때문이에요.
     * 받는 것: 목표 비율, 손절 비율이에요. 돌려주는 것: 없어요. 다르면 IllegalStateException을 던져요.
     */
    public void verifyThresholds(BigDecimal target, BigDecimal stop) {
        if (targetRate.compareTo(target) != 0 || stopRate.compareTo(stop) != 0)
            throw new IllegalStateException("Candidate observation thresholds are fixed for this run");
    }

    /**
     * 하는 일: 재평가 결과(상태, 결측 구간, 가상 진입, 청산 결과, 비용 가정)를 기록에 반영해요.
     * 받는 것: 평가시각, 상태, 결측 구간 JSON, 진입시각·가격, 청산 결과(없을 수 있음), 비용 가정 JSON이에요.
     * 돌려주는 것: 없어요.
     */
    public void observe(Instant at, OvernightPerformanceStatus status, String missing,
            Instant entryAt, BigDecimal entryPrice,
            OvernightExecutionResult result, String assumption) {
        this.evaluatedAt = at;
        this.status = status;
        this.missingIntervals = missing;
        this.entryAt = entryAt;
        this.entryPrice = entryPrice;
        this.costAssumption = assumption;
        // 청산 결과가 없으면 왜 없는지(진입가 없음/청산 자료 없음)만 남기고, 이전 청산 값은 건드리지 않아요.
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
        // 비용이 0으로 설정된 결과는 "비용 미검증"으로 구분해 표시해요.
        this.costStatus = result.costsApplied() ? "COSTS_APPLIED" : "ZERO_COSTS_UNVERIFIED";
    }

    /** 처음 저장(prePersist)·수정(preUpdate) 직전에 생성·수정 시각을 자동으로 채워요. 받는 것/돌려주는 것은 없어요. */
    @PrePersist void prePersist() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }

    /**
     * 다음 거래일 가상 청산 결과를 담는 값 묶음이에요(청산시각, 청산가, 비용 전/후 수익률, 청산 이유, 봉 안 순서 모호 여부, 비용 반영 여부).
     * 택배 송장처럼 결과를 한 장에 묶어 observe()에 넘기는 용도예요.
     */
    public record OvernightExecutionResult(Instant exitAt, BigDecimal exitPrice, BigDecimal grossReturnRate,
            BigDecimal netReturnRate, String exitReason, boolean ambiguous, boolean costsApplied) { }
    /** 아래는 저장된 값을 그대로 돌려주는 조회용 getter들이에요. */
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
