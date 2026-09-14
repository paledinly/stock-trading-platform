package com.sunmo.stockplatform.analytics.infrastructure;
import java.math.BigDecimal;
import java.time.Instant;
import com.sunmo.stockplatform.analytics.domain.PerformanceStatus;
import com.sunmo.stockplatform.scanner.domain.ScannerType;
public interface AnalyticsRow {
    PerformanceStatus getStatus();
    String getCalculationVersion();
    BigDecimal getReturn5m();
    BigDecimal getReturn10m();
    BigDecimal getReturn30m();
    BigDecimal getReturn60m();
    BigDecimal getReturnClose();
    BigDecimal getMaxReturn();
    BigDecimal getMaxDrawdown();
    BigDecimal getMfe();
    BigDecimal getMae();
    Instant getDetectedAt();
    ScannerType getType();
    BigDecimal getOpportunityScore();
    BigDecimal getRiskScore();
}
