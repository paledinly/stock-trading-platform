package com.sunmo.stockplatform.candle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "market.backfill")
public record CandleBackfillProperties(boolean enabled, Duration refreshInterval, int maxRequestsPerQuery) {
    public CandleBackfillProperties {
        refreshInterval = refreshInterval == null ? Duration.ofMinutes(1) : refreshInterval;
        maxRequestsPerQuery = Math.max(1, maxRequestsPerQuery);
    }
}
