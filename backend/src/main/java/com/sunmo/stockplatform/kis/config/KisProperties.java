package com.sunmo.stockplatform.kis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "kis")
public record KisProperties(
        boolean enabled,
        URI baseUrl,
        String appKey,
        String appSecret,
        Duration tokenRefreshSkew,
        Duration connectTimeout,
        Duration readTimeout,
        Master master) {
    public record Master(boolean syncEnabled, String cron, URI kospiUrl, URI kosdaqUrl) {
    }

    public void requireCredentials() {
        if (!enabled || appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalStateException("KIS integration is disabled or credentials are missing");
        }
    }
}
