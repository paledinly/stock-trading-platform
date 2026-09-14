package com.sunmo.stockplatform.kis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "kis.requests")
public record KisRequestProperties(Duration minimumInterval, Duration quoteInterval, Duration rateLimitBackoff,
        int maxRetries) {
    public KisRequestProperties {
        minimumInterval = minimumInterval == null ? Duration.ofMillis(125) : minimumInterval;
        quoteInterval = quoteInterval == null ? Duration.ofMillis(250) : quoteInterval;
        rateLimitBackoff = rateLimitBackoff == null ? Duration.ofMillis(500) : rateLimitBackoff;
        if (minimumInterval.isNegative() || quoteInterval.isNegative() || rateLimitBackoff.isNegative()
                || minimumInterval.compareTo(Duration.ofSeconds(2)) > 0 || quoteInterval.compareTo(Duration.ofSeconds(2)) > 0
                || rateLimitBackoff.compareTo(Duration.ofSeconds(5)) > 0 || maxRetries < 0 || maxRetries > 2)
            throw new IllegalArgumentException("Invalid KIS pacing/retry configuration");
    }
}
