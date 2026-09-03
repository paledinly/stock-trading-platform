package com.sunmo.stockplatform.scanner;

import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;
import com.sunmo.stockplatform.scanner.application.ScannerEvaluator;
import com.sunmo.stockplatform.scanner.score.OpportunityRiskScorer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class OpportunityRiskScorerTest {
    private final OpportunityRiskScorer scorer = new OpportunityRiskScorer();

    @Test
    void scoresOpportunityAndRiskSeparately() {
        var metrics = new ScannerEvaluator.Metrics(true, bd("2.5"), bd("3.0"), bd("5.5"), "READY",
                bd("100"), bd("95"));
        var score = scorer.score(metrics, feature(bd("2.0"), bd("145"), bd("0.2"), 30, 10));

        assertThat(score.opportunityScore()).isGreaterThan(bd("60"));
        assertThat(score.riskScore()).isLessThan(bd("10"));
        assertThat(score.opportunityFactors()).containsKeys("priceMomentum", "volumeExpansion", "vwapLeadership");
        assertThat(score.riskFactors()).containsKeys("vwapOverextension", "sellPressure");
        assertThat(score.scoreVersion()).isEqualTo("opportunity-risk-v1");
    }

    @Test
    void raisesRiskForWeakAndOverextendedSignals() {
        var metrics = new ScannerEvaluator.Metrics(true, bd("-1.2"), bd("1.1"), bd("-0.1"), "READY",
                bd("100"), bd("95"));
        var score = scorer.score(metrics, feature(bd("5.5"), bd("72"), bd("4.0"), 5, 25));

        assertThat(score.riskScore()).isGreaterThan(bd("45"));
        assertThat(score.opportunityScore()).isLessThan(bd("25"));
    }

    private MarketFeatureSnapshot feature(BigDecimal vwapDistance, BigDecimal strength, BigDecimal highDistance,
                                          long buyDelta, long sellDelta) {
        return new MarketFeatureSnapshot("005930", LocalDate.parse("2026-09-03"), Instant.parse("2026-09-03T01:40:00Z"),
                bd("105"), 1000, bd("105000"), bd("100"), bd("106"), bd("99"), bd("103"),
                vwapDistance, bd("0.1"), bd("3.0"), bd("2.5"), strength, buyDelta, sellDelta,
                bd("0.55"), highDistance, false, bd("100"), MarketFeatureSnapshot.VERSION);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
