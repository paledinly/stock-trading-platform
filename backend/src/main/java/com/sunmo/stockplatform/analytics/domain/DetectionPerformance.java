package com.sunmo.stockplatform.analytics.domain;

import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import jakarta.persistence.*;
import java.math.*;
import java.time.*;

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
    @Version
    private long version;
    @Column(name = "recovery_used", nullable = false)
    private boolean recoveryUsed;
    @Column(name = "finalization_reason", length = 30)
    private String finalizationReason;

    protected DetectionPerformance() {
    }

    public DetectionPerformance(ScannerDetection detection) {
        this.detection = detection;
        this.status = PerformanceStatus.PENDING;
        this.calculationVersion = "performance-v2";
        this.highestPrice = detection.getDetectedPrice();
        this.lowestPrice = detection.getDetectedPrice();
        this.updatedAt = Instant.now();
        recalculate();
    }

    public synchronized boolean observe(BigDecimal price, Instant at) {
        return observeInternal(price, price, price, at, false);
    }

    public synchronized boolean observeRange(BigDecimal high, BigDecimal low, BigDecimal close, Instant at) {
        return observeInternal(high, low, close, at, true);
    }

    private boolean observeInternal(BigDecimal high, BigDecimal low, BigDecimal close, Instant at, boolean recovered) {
        if (status != PerformanceStatus.PENDING || at.isBefore(detection.getDetectedAt()))
            return false;
        BigDecimal oldHigh = highestPrice, oldLow = lowestPrice;
        boolean milestone = false;
        highestPrice = highestPrice.max(high);
        lowestPrice = lowestPrice.min(low);
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

    public synchronized void finalizeClose(BigDecimal price, Instant at) {
        finalizeClose(price, at, "MARKET_CLOSE");
    }

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

    private boolean allMilestones() {
        return price5m != null && price10m != null && price30m != null && price60m != null && closePrice != null;
    }

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

    private BigDecimal pct(BigDecimal value, BigDecimal base) {
        return value == null ? null
                : value.subtract(base).divide(base, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                        .setScale(6, RoundingMode.HALF_UP);
    }

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
