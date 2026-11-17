/*
 * 시장 전체 스캔 후보를 순위표 대신 "장 전 변동성 명단 + 오늘 상승률 상위"로 만들 때 쓰는 설정값(market.wide.volatility.*)이에요.
 * 근거: audit/2026-11-17-subscription-criteria (2018~2026 일봉, 다음 날 시가 상위 20종목을 가장 많이 담은 기준).
 */
package com.sunmo.stockplatform.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * enabled: 켜면 변동성 명단을 기본 후보로 써요(꺼지거나 명단이 비면 기존 순위표 방식).
 * size: 장 전 명단 크기(기본 300), lookbackDays: 변동성을 잴 거래일 수(기본 20),
 * minAverageTradingValue: 같은 기간 평균 거래대금 하한(기본 10억 원),
 * gainersPerMarket: 코스피·코스닥 각각 오늘 상승률 순위표에서 더할 개수(기본 30, KIS 최대 30행),
 * quoteMaxAge: 명단 종목 시세를 다시 조회하지 않고 쓸 최대 나이(기본 10분). 360종목을 매 스캔 새로 받을 수 없어서예요.
 */
@ConfigurationProperties(prefix = "market.wide.volatility")
public record VolatilityUniverseProperties(boolean enabled, int size, int lookbackDays, Long minAverageTradingValue,
        int gainersPerMarket, Duration quoteMaxAge) {
    /** 하는 일: 빈 값에 기본값을 채우고 범위를 검사해요. 잘못된 설정이면 서버 시작 때 실패해요. */
    public VolatilityUniverseProperties {
        size = size <= 0 ? 300 : size;
        lookbackDays = lookbackDays <= 0 ? 20 : lookbackDays;
        minAverageTradingValue = minAverageTradingValue == null ? 1_000_000_000L : minAverageTradingValue;
        gainersPerMarket = gainersPerMarket < 0 ? 0 : gainersPerMarket == 0 ? 30 : gainersPerMarket;
        quoteMaxAge = quoteMaxAge == null ? Duration.ofMinutes(10) : quoteMaxAge;
        if (size > 500 || lookbackDays < 5 || lookbackDays > 60 || minAverageTradingValue < 0 || gainersPerMarket > 30
                || quoteMaxAge.isNegative())
            throw new IllegalArgumentException("Invalid volatility universe configuration");
    }
}
