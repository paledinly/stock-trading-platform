/*
 * 스캐너가 종목을 감지한 뒤 가격이 어떻게 움직였는지(5분·10분·30분·60분 뒤, 장 마감 가격, 최고·최저가)를 기록하는 파일이에요.
 * 감지 가격을 기준으로 각 시점의 수익률(%)을 계산해 DB 테이블 detection_performance 한 줄로 저장해요.
 * DetectionPerformanceTracker가 틱과 저장된 5분봉으로 값을 채우고, ScannerAnalyticsService가 읽어서 통계를 만들어요.
 */
package com.sunmo.stockplatform.analytics.domain;

import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import jakarta.persistence.*;
import java.math.*;
import java.time.*;

/**
 * 감지 한 건의 "그 뒤 성과"를 담는 엔티티(DB 테이블 한 줄과 짝지어진 자바 객체)예요.
 * 감지 시점을 출발선으로 정해 두고 5분·10분·30분·60분·마감 때마다 가격을 받아 적는 기록 카드라고 생각하면 돼요.
 * 한 번 받아 적은 시점 가격은 다시 덮어쓰지 않고, 최고가·최저가만 계속 갱신해요.
 */
@Entity
@Table(name = "detection_performance")
public class DetectionPerformance {
    @Id
    @Column(name = "detection_id")
    private Long detectionId;
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "detection_id")
    private ScannerDetection detection;
    @Column(name = "price_5m", precision = 20, scale = 4)
    private BigDecimal price5m;
    @Column(name = "price_10m", precision = 20, scale = 4)
    private BigDecimal price10m;
    @Column(name = "price_30m", precision = 20, scale = 4)
    private BigDecimal price30m;
    @Column(name = "price_60m", precision = 20, scale = 4)
    private BigDecimal price60m;
    @Column(name = "close_price", precision = 20, scale = 4)
    private BigDecimal closePrice;
    @Column(name = "highest_price", precision = 20, scale = 4)
    private BigDecimal highestPrice;
    @Column(name = "lowest_price", precision = 20, scale = 4)
    private BigDecimal lowestPrice;
    @Column(name = "return_5m", precision = 12, scale = 6)
    private BigDecimal return5m;
    @Column(name = "return_10m", precision = 12, scale = 6)
    private BigDecimal return10m;
    @Column(name = "return_30m", precision = 12, scale = 6)
    private BigDecimal return30m;
    @Column(name = "return_60m", precision = 12, scale = 6)
    private BigDecimal return60m;
    @Column(name = "return_close", precision = 12, scale = 6)
    private BigDecimal returnClose;
    @Column(name = "max_return", precision = 12, scale = 6)
    private BigDecimal maxReturn;
    @Column(name = "max_drawdown", precision = 12, scale = 6)
    private BigDecimal maxDrawdown;
    @Column(precision = 12, scale = 6)
    private BigDecimal mfe;
    @Column(precision = 12, scale = 6)
    private BigDecimal mae;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PerformanceStatus status;
    @Column(name = "calculation_version", nullable = false)
    private String calculationVersion;
    @Column(name = "observed_5m_at")
    private Instant observed5mAt;
    @Column(name = "observed_10m_at")
    private Instant observed10mAt;
    @Column(name = "observed_30m_at")
    private Instant observed30mAt;
    @Column(name = "observed_60m_at")
    private Instant observed60mAt;
    @Column(name = "close_observed_at")
    private Instant closeObservedAt;
    @Column(name = "finalized_at")
    private Instant finalizedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    // 낙관적 잠금용 버전 번호예요. 다른 곳에서 먼저 저장했으면 저장 시 충돌 예외가 나요.
    @Version
    private long version;
    @Column(name = "recovery_used", nullable = false)
    private boolean recoveryUsed;
    @Column(name = "finalization_reason", length = 30)
    private String finalizationReason;

    /** JPA(DB와 자바 객체를 이어 주는 도구)가 DB에서 읽어 올 때만 쓰는 빈 생성자예요. */
    protected DetectionPerformance() {
    }

    /**
     * 새 감지에 대한 성과 기록을 PENDING(추적 중) 상태로 시작해요.
     * 받는 것: 성과를 추적할 감지(detection). 최고·최저가는 감지 가격으로 시작해요.
     * 돌려주는 것: 아직 저장되지 않은 새 성과 객체예요.
     */
    public DetectionPerformance(ScannerDetection detection) {
        this.detection = detection;
        this.status = PerformanceStatus.PENDING;
        this.calculationVersion = "performance-v2";
        this.highestPrice = detection.getDetectedPrice();
        this.lowestPrice = detection.getDetectedPrice();
        this.updatedAt = Instant.now();
        recalculate();
    }

    /**
     * 실시간 체결가 하나를 반영해요.
     * 받는 것: 체결 가격(price)과 체결 발생 시각(at).
     * 돌려주는 것: 최고·최저가나 시점 가격이 바뀌었으면 true예요(저장이 필요하다는 뜻).
     */
    public synchronized boolean observe(BigDecimal price, Instant at) {
        return observeInternal(price, price, price, at, false);
    }

    /**
     * 저장된 확정 5분봉 하나로 빠진 구간을 복구 반영해요.
     * 받는 것: 봉의 고가·저가·종가와 봉이 끝난 시각(at).
     * 돌려주는 것: 값이 바뀌었으면 true예요. 바뀌면 recoveryUsed 표시가 켜져요.
     */
    public synchronized boolean observeRange(BigDecimal high, BigDecimal low, BigDecimal close, Instant at) {
        return observeInternal(high, low, close, at, true);
    }

    /**
     * 틱과 봉 반영의 공통 처리예요. 최고·최저가를 넓히고, 경과 시간이 처음 넘은 시점의 가격을 기록해요.
     * 받는 것: 고가·저가·종가, 관측 시각, 복구 데이터인지 여부.
     * 돌려주는 것: 바뀐 것이 있으면 true예요. 추적이 끝났거나 감지 전 데이터면 false예요.
     */
    private boolean observeInternal(BigDecimal high, BigDecimal low, BigDecimal close, Instant at, boolean recovered) {
        // 이미 확정된 기록이나 감지 이전 시각의 데이터는 반영하지 않아요.
        if (status != PerformanceStatus.PENDING || at.isBefore(detection.getDetectedAt()))
            return false;
        BigDecimal oldHigh = highestPrice, oldLow = lowestPrice;
        boolean milestone = false;
        highestPrice = highestPrice.max(high);
        lowestPrice = lowestPrice.min(low);
        // 감지 후 처음으로 5/10/30/60분을 넘긴 관측의 종가를 그 시점 가격으로 한 번만 적어요(이후 덮어쓰지 않음).
        long seconds = Duration.between(detection.getDetectedAt(), at).getSeconds();
        if (seconds >= 300 && price5m == null) {
            price5m = close;
            observed5mAt = at;
            milestone = true;
        }
        if (seconds >= 600 && price10m == null) {
            price10m = close;
            observed10mAt = at;
            milestone = true;
        }
        if (seconds >= 1800 && price30m == null) {
            price30m = close;
            observed30mAt = at;
            milestone = true;
        }
        if (seconds >= 3600 && price60m == null) {
            price60m = close;
            observed60mAt = at;
            milestone = true;
        }
        boolean changed = milestone || oldHigh.compareTo(highestPrice) != 0 || oldLow.compareTo(lowestPrice) != 0;
        if (changed) {
            recoveryUsed |= recovered;
            recalculate();
            updatedAt = Instant.now();
        }
        return changed;
    }

    /**
     * 장 마감 가격으로 기록을 확정해요. 사유는 MARKET_CLOSE로 남아요.
     * 받는 것: 마감 가격(없으면 null)과 확정 시각.
     * 돌려주는 것: 없어요. 상태가 COMPLETED 또는 DATA_MISSING으로 바뀌어요.
     */
    public synchronized void finalizeClose(BigDecimal price, Instant at) {
        finalizeClose(price, at, "MARKET_CLOSE");
    }

    /**
     * 마감 가격과 확정 사유를 받아 기록을 끝내요.
     * 받는 것: 마감 가격(null 가능), 확정 시각, 사유(예: MARKET_CLOSE, STARTUP_RECOVERY).
     * 돌려주는 것: 없어요. 모든 시점 가격이 있으면 COMPLETED, 하나라도 빠지면 DATA_MISSING이 돼요.
     */
    public synchronized void finalizeClose(BigDecimal price, Instant at, String reason) {
        if (status != PerformanceStatus.PENDING)
            return;
        if (price != null) {
            highestPrice = highestPrice.max(price);
            lowestPrice = lowestPrice.min(price);
            closePrice = price;
            closeObservedAt = at;
        }
        recalculate();
        status = allMilestones() ? PerformanceStatus.COMPLETED : PerformanceStatus.DATA_MISSING;
        finalizationReason = reason;
        finalizedAt = at;
        updatedAt = at;
    }

    /**
     * 하는 일: 5·10·30·60분 가격과 마감 가격이 모두 채워졌는지 확인해요.
     * 돌려주는 것: 모두 있으면 true예요.
     */
    private boolean allMilestones() {
        return price5m != null && price10m != null && price30m != null && price60m != null && closePrice != null;
    }

    /**
     * 하는 일: 감지 가격 대비 각 가격의 수익률(%)을 다시 계산해요.
     * MFE(보유 중 최대 유리 폭)는 최고가 수익률, MAE(보유 중 최대 불리 폭)는 최저가 수익률과 같아요.
     */
    private void recalculate() {
        BigDecimal base = detection.getDetectedPrice();
        return5m = pct(price5m, base);
        return10m = pct(price10m, base);
        return30m = pct(price30m, base);
        return60m = pct(price60m, base);
        returnClose = pct(closePrice, base);
        maxReturn = pct(highestPrice, base);
        maxDrawdown = pct(lowestPrice, base);
        mfe = maxReturn;
        mae = maxDrawdown;
    }

    /**
     * 하는 일: (value - base) / base × 100 으로 변화율(%)을 소수 6자리까지 계산해요.
     * 받는 것: 비교할 가격(value)과 기준 가격(base). 돌려주는 것: 변화율, value가 없으면 null이에요.
     * ⚠️ 확인 필요: base(감지 가격)가 0이면 나눗셈 예외가 나요. 감지 가격이 항상 0보다 큰지 확인이 필요해요.
     */
    private BigDecimal pct(BigDecimal value, BigDecimal base) {
        return value == null ? null
                : value.subtract(base).divide(base, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                        .setScale(6, RoundingMode.HALF_UP);
    }

    // 아래는 저장된 값을 그대로 꺼내 주는 조회용 메서드(getter)들이에요. 가격, 수익률(%), 관측 시각, 상태를 돌려줘요.

    public Long getDetectionId() {
        return detectionId;
    }

    public ScannerDetection getDetection() {
        return detection;
    }

    public BigDecimal getPrice5m() {
        return price5m;
    }

    public BigDecimal getPrice10m() {
        return price10m;
    }

    public BigDecimal getPrice30m() {
        return price30m;
    }

    public BigDecimal getPrice60m() {
        return price60m;
    }

    public BigDecimal getClosePrice() {
        return closePrice;
    }

    public BigDecimal getHighestPrice() {
        return highestPrice;
    }

    public BigDecimal getLowestPrice() {
        return lowestPrice;
    }

    public BigDecimal getReturn5m() {
        return return5m;
    }

    public BigDecimal getReturn10m() {
        return return10m;
    }

    public BigDecimal getReturn30m() {
        return return30m;
    }

    public BigDecimal getReturn60m() {
        return return60m;
    }

    public BigDecimal getReturnClose() {
        return returnClose;
    }

    public BigDecimal getMaxReturn() {
        return maxReturn;
    }

    public BigDecimal getMaxDrawdown() {
        return maxDrawdown;
    }

    public BigDecimal getMfe() {
        return mfe;
    }

    public BigDecimal getMae() {
        return mae;
    }

    public Instant getObserved5mAt() {
        return observed5mAt;
    }

    public Instant getObserved10mAt() {
        return observed10mAt;
    }

    public Instant getObserved30mAt() {
        return observed30mAt;
    }

    public Instant getObserved60mAt() {
        return observed60mAt;
    }

    public Instant getCloseObservedAt() {
        return closeObservedAt;
    }

    public boolean isRecoveryUsed() {
        return recoveryUsed;
    }

    public String getFinalizationReason() {
        return finalizationReason;
    }

    public PerformanceStatus getStatus() {
        return status;
    }

    public String getCalculationVersion() {
        return calculationVersion;
    }

    public Instant getFinalizedAt() {
        return finalizedAt;
    }
}
