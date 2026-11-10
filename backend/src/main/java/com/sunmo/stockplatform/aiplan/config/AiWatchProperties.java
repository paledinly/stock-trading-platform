/*
 * AI 장전 계획 2단계(실시간 감시·가상 매수) 설정이에요. AiWatchService가 써요.
 */
package com.sunmo.stockplatform.aiplan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * enabled: 장중 감시 여부(기본 켜짐). positionAmount: 가상 매수 1회 금액(원, 기본 1,000만 원, 사용자 결정 2026-11-10).
 */
@ConfigurationProperties(prefix = "ai-plan.watch")
public record AiWatchProperties(Boolean enabled, BigDecimal positionAmount) {
    public AiWatchProperties {
        enabled = enabled == null || enabled;
        positionAmount = positionAmount == null ? new BigDecimal("10000000") : positionAmount;
        if (positionAmount.signum() <= 0) throw new IllegalArgumentException("ai-plan.watch.position-amount must be positive");
    }
}
