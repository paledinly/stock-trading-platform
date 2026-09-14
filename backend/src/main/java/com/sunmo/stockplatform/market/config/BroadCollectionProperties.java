package com.sunmo.stockplatform.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "market.wide.collection")
public record BroadCollectionProperties(int rankingLimit, int detailQuoteBudget, Duration cacheMaxAge,
        Duration tickMaxAge, boolean excludeStructuredNames) {
    public BroadCollectionProperties {
        rankingLimit = rankingLimit <= 0 ? 100 : rankingLimit;
        cacheMaxAge = cacheMaxAge == null ? Duration.ofSeconds(60) : cacheMaxAge;
        tickMaxAge = tickMaxAge == null ? Duration.ofSeconds(30) : tickMaxAge;
        if (rankingLimit > 100 || detailQuoteBudget < 0 || detailQuoteBudget > 120
                || cacheMaxAge.isNegative() || tickMaxAge.isNegative())
            throw new IllegalArgumentException("Invalid Broad collection configuration");
    }
}
