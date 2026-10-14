/*
 * Broad 후보의 보강(enrichment) 작업 큐 설정값(market.wide.enrichment.*)을 담아요.
 * 큐 크기, 재시도 횟수, 결과 유효 시간, 재시도 간격의 기본값과 허용 범위를 정해요.
 * marketwide 패키지의 BroadEnrichmentQueue가 이 값을 읽어 동작해요.
 */
package com.sunmo.stockplatform.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * 보강 작업 대기열 규칙이에요. 세탁소 접수 규칙처럼 "한 번에 몇 벌까지 받고(capacity), 몇 번까지 다시 시도하고(maxAttempts),
 * 맡긴 지 얼마나 지나면 무효로 보고(maxAge), 실패 후 얼마 뒤 재시도할지(retryDelay)"를 정해요.
 * 기본값: capacity 200, maxAttempts 3, maxAge 3분, retryDelay 10초.
 */
@ConfigurationProperties("market.wide.enrichment")
public record BroadEnrichmentProperties(boolean enabled, int capacity, int maxAttempts,
        Duration maxAge, Duration retryDelay) {
    /**
     * 하는 일: 0이나 null인 값에 기본값을 채우고 범위를 검사해요.
     * 범위: capacity 1~1000, maxAttempts 1~5, maxAge 0 초과 15분 이하, retryDelay 0 초과. 벗어나면 예외를 던져요.
     */
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
