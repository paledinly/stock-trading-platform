/*
 * 연구용 장중 분봉 수집 설정이에요. IntradayResearchService가 얼마나 과거부터 받을지와 대상 종목 기준을 정할 때 써요.
 */
package com.sunmo.stockplatform.candle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * enabled: 자동 수집 여부, lookbackDays: 오늘부터 며칠 전 날짜까지 받을지(KIS 과거 분봉은 약 1년만 줘요),
 * previousDayValue: 전날 거래대금 하한, sameDayValue: 당일 거래대금 하한(둘 중 하나만 넘으면 대상).
 */
@ConfigurationProperties(prefix = "market.intraday-research")
public record IntradayResearchProperties(boolean enabled, int lookbackDays, BigDecimal previousDayValue,
        BigDecimal sameDayValue) {
    /** 하는 일: 빈 값에 기본값을 넣어요(364일, 전날 5억 원, 당일 10억 원). */
    public IntradayResearchProperties {
        lookbackDays = lookbackDays <= 0 ? 364 : Math.min(lookbackDays, 400);
        previousDayValue = previousDayValue == null ? new BigDecimal("500000000") : previousDayValue;
        sameDayValue = sameDayValue == null ? new BigDecimal("1000000000") : sameDayValue;
    }
}
