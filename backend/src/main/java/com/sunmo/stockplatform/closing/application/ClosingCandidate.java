package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.closing.domain.ClosingCandidateSource;
import com.sunmo.stockplatform.marketwide.domain.MarketBroadSnapshot;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import com.sunmo.stockplatform.stock.domain.Stock;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public sealed interface ClosingCandidate permits ClosingCandidate.Precision, ClosingCandidate.Broad {
    Stock stock();
    Instant observedAt();
    BigDecimal referencePrice();
    BigDecimal opportunityScore();
    BigDecimal riskScore();
    ClosingCandidateSource source();
    String dataQuality();
    int coverageMinutes();
    List<String> missingFeatures();

    record Precision(ScannerDetection detection, int coverageMinutes, List<String> missingFeatures)
            implements ClosingCandidate {
        public Stock stock() { return detection.getStock(); }
        public Instant observedAt() { return detection.getDetectedAt(); }
        public BigDecimal referencePrice() { return detection.getDetectedPrice(); }
        public BigDecimal opportunityScore() { return detection.getOpportunityScore(); }
        public BigDecimal riskScore() { return detection.getRiskScore(); }
        public ClosingCandidateSource source() { return ClosingCandidateSource.PRECISION; }
        public String dataQuality() { return missingFeatures.isEmpty() ? "PRECISION_A" : "PRECISION_B"; }
    }

    record Broad(MarketBroadSnapshot snapshot, BigDecimal riskScore, List<String> missingFeatures) implements ClosingCandidate {
        public Stock stock() { return snapshot.getStock(); }
        public Instant observedAt() { return snapshot.getCapturedAt(); }
        public BigDecimal referencePrice() { return snapshot.getCurrentPrice(); }
        public BigDecimal opportunityScore() { return snapshot.getBroadScore(); }
        public ClosingCandidateSource source() { return ClosingCandidateSource.BROAD; }
        public String dataQuality() { return snapshot.getDataQuality().name(); }
        public int coverageMinutes() { return 0; }
    }
}
