/*
 * 당일 5분봉 보충(backfill: 빠진 봉을 KIS 과거 분봉으로 나중에 채우기) 설정값(market.backfill.*)을 담아요.
 * CandleBackfillService가 같은 종목을 얼마나 자주 다시 시도할지, 한 번에 KIS를 몇 번 부를지 정할 때 써요.
 */
package com.sunmo.stockplatform.candle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 분봉 보충 규칙이에요. enabled: 켜기/끄기, refreshInterval: 같은 종목 재시도 최소 간격(기본 1분),
 * maxRequestsPerQuery: 한 번 보충할 때 KIS 분봉 페이지 요청 최대 횟수(최소 1)예요.
 */
@ConfigurationProperties(prefix = "market.backfill")
public record CandleBackfillProperties(boolean enabled, Duration refreshInterval, int maxRequestsPerQuery) {
    /** 하는 일: 재시도 간격이 비면 1분으로, 요청 횟수는 최소 1로 맞춰요. */
    public CandleBackfillProperties {
        refreshInterval = refreshInterval == null ? Duration.ofMinutes(1) : refreshInterval;
        maxRequestsPerQuery = Math.max(1, maxRequestsPerQuery);
    }
}
