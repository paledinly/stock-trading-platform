package com.sunmo.stockplatform.scanner.score;

import java.math.BigDecimal;
import java.util.Map;

public record OpportunityRiskScore(
        BigDecimal opportunityScore,
        BigDecimal riskScore,
        Map<String, BigDecimal> opportunityFactors,
        Map<String, BigDecimal> riskFactors,
        String scoreVersion) {
    public static final String VERSION = "opportunity-risk-v1";
}
