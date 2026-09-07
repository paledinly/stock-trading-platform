package com.sunmo.stockplatform.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;

@ConfigurationProperties(prefix = "market.precision")
public record PrecisionSubscriptionProperties(boolean enabled, int maxSubscriptions, int reserve,
        Duration minHold, BigDecimal replaceMargin, LocalTime freezeAt) {
    public PrecisionSubscriptionProperties {
        maxSubscriptions = maxSubscriptions <= 0 ? 28 : maxSubscriptions;
        reserve = Math.max(0, reserve);
        minHold = minHold == null ? Duration.ofMinutes(15) : minHold;
        replaceMargin = replaceMargin == null ? BigDecimal.TEN : replaceMargin;
        freezeAt = freezeAt == null ? LocalTime.of(14, 50) : freezeAt;
    }
}
