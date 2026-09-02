package com.sunmo.stockplatform.market.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;
@ConfigurationProperties(prefix="market.realtime")
public record RealtimeMarketProperties(boolean enabled, URI websocketUrl, Duration candleWatermark,
                                       Duration quoteTtl, int replaySize, int subscriptionLimit) {
    public RealtimeMarketProperties {
        subscriptionLimit = subscriptionLimit <= 0 ? 41 : subscriptionLimit;
    }
}
