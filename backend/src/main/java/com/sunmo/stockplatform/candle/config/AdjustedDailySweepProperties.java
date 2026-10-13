package com.sunmo.stockplatform.candle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Adjusted daily bars for every listed non-ETF/ETN stock. lookbackDays is the calendar history kept per stock
 * (210 ≈ 140 trading days); refreshDays is the recent calendar window re-read each day (one KIS call, ≤ 100 rows).
 */
@ConfigurationProperties(prefix = "market.adjusted-daily")
public record AdjustedDailySweepProperties(boolean enabled, int lookbackDays, int refreshDays) {
    public AdjustedDailySweepProperties {
        lookbackDays = lookbackDays <= 0 ? 210 : Math.min(Math.max(lookbackDays, 30), 400);
        refreshDays = refreshDays <= 0 ? 140 : Math.min(refreshDays, 140);
    }
}
