package com.sunmo.stockplatform.candle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "closing.daily-backfill")
public record DailyCandleBackfillProperties(boolean enabled, int maxStocks, int lookbackDays,
        Duration refreshInterval) {
    public DailyCandleBackfillProperties {
        maxStocks = maxStocks <= 0 ? 40 : Math.min(maxStocks, 50);
        lookbackDays = lookbackDays <= 0 ? 120 : Math.min(lookbackDays, 140);
        refreshInterval = refreshInterval == null ? Duration.ofHours(1) : refreshInterval;
    }
}
