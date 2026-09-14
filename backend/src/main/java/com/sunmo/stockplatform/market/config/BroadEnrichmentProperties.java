package com.sunmo.stockplatform.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("market.wide.enrichment")
public record BroadEnrichmentProperties(boolean enabled, int capacity, int maxAttempts,
        Duration maxAge, Duration retryDelay) {
    public BroadEnrichmentProperties {
        capacity = capacity == 0 ? 200 : capacity;
        maxAttempts = maxAttempts == 0 ? 3 : maxAttempts;
        maxAge = maxAge == null ? Duration.ofMinutes(3) : maxAge;
        retryDelay = retryDelay == null ? Duration.ofSeconds(10) : retryDelay;
        if (capacity < 1 || capacity > 1000 || maxAttempts < 1 || maxAttempts > 5
                || maxAge.isNegative() || maxAge.isZero() || maxAge.compareTo(Duration.ofMinutes(15)) > 0
                || retryDelay.isNegative() || retryDelay.isZero())
            throw new IllegalArgumentException("Invalid broad enrichment configuration");
    }
}
