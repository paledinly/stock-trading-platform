package com.sunmo.stockplatform.watchlist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "watchlist.realtime")
public record WatchlistRealtimeProperties(int maxPinned) {
    public WatchlistRealtimeProperties {
        if (maxPinned < 0) throw new IllegalArgumentException("watchlist.realtime.max-pinned must be non-negative");
    }
}
