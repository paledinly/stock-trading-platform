/*
 * 마감 평가에 들어오는 후보 한 건을 같은 모양으로 다루기 위한 공통 인터페이스예요.
 * 정밀(PRECISION) 후보(실시간 구독 종목의 확정 5분봉에서 Scanner가 잡은 탐지)와
 * BROAD 후보(시장 전체 순위 스캔 스냅샷)를 하나의 타입으로 묶어요.
 * ClosingRecommendationService와 ClosingPrecisionEvaluator가 후보를 모으고 평가할 때 써요.
 */
package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.closing.domain.ClosingCandidateSource;
import com.sunmo.stockplatform.marketwide.domain.MarketBroadSnapshot;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import com.sunmo.stockplatform.stock.domain.Stock;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 마감 평가 후보의 공통 모양이에요. Precision과 Broad 두 종류만 허용해요(sealed).
 * 출처가 다른 두 지원서(정밀 면접 기록, 서류 요약본)를 같은 양식의 표지에 옮겨 적어
 * 심사원이 "종목, 관측 시각, 기준가, 점수"를 똑같이 읽을 수 있게 해 주는 표지라고 생각하면 돼요.
 */
public sealed interface ClosingCandidate permits ClosingCandidate.Precision, ClosingCandidate.Broad {
    // 공통 항목: 종목, 관측 시각, 기준 가격, 기회 점수, 위험 점수, 출처, 데이터 품질, 관측 커버리지(분), 빠진 Feature 목록.
    Stock stock();
    Instant observedAt();
    BigDecimal referencePrice();
    BigDecimal opportunityScore();
    BigDecimal riskScore();
    ClosingCandidateSource source();
    String dataQuality();
    int coverageMinutes();
    List<String> missingFeatures();

    /**
     * 정밀(PRECISION) 후보예요. Scanner 탐지 1건을 감싸고, 탐지 시각·가격·점수를 그대로 꺼내 써요.
     * 빠진 Feature가 없으면 품질 PRECISION_A, 하나라도 있으면 PRECISION_B예요.
     */
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

    /**
     * BROAD 후보예요. 시장 전체 스캔 스냅샷 1건과 따로 계산한 위험 점수를 감싸요.
     * 실시간 분봉 관측이 없으므로 커버리지는 항상 0분이에요.
     */
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
