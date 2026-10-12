package com.sunmo.stockplatform.closing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "closing.automation")
public record ClosingAutomationProperties(
        boolean enabled,
        int limit,
        BigDecimal minimumOpportunity,
        BigDecimal maximumRisk,
        BigDecimal targetRate,
        BigDecimal stopRate,
        Integer observationSubscriptionLimit) {

    public ClosingAutomationProperties {
        limit = limit <= 0 ? 10 : Math.min(limit, 30);
        minimumOpportunity = minimumOpportunity == null ? new BigDecimal("35") : minimumOpportunity;
        maximumRisk = maximumRisk == null ? new BigDecimal("65") : maximumRisk;
        targetRate = targetRate == null ? new BigDecimal("3") : targetRate;
        stopRate = stopRate == null ? new BigDecimal("-2") : stopRate;
        // Unselected SELECTED/WATCH candidates kept on realtime for paper entry and next-session bars; 0 disables.
        observationSubscriptionLimit = observationSubscriptionLimit == null ? 10 : observationSubscriptionLimit;
        if (observationSubscriptionLimit < 0)
            throw new IllegalArgumentException("Invalid closing observation subscription limit");
        if (minimumOpportunity.signum() < 0 || maximumRisk.signum() < 0
                || targetRate.signum() <= 0 || stopRate.signum() >= 0)
            throw new IllegalArgumentException("Invalid closing automation thresholds");
    }
}
