/*
 * 연구용 수정주가 일봉 전 종목 수집 설정값(market.adjusted-daily.*)을 담아요.
 * AdjustedDailySweepService가 처음 받을 기간과 매일 다시 읽을 최근 기간을 정할 때 사용해요.
 */
package com.sunmo.stockplatform.candle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Adjusted daily bars for every listed non-ETF/ETN stock. lookbackDays is the calendar history kept per stock
 * (210 ≈ 140 trading days); refreshDays is the recent calendar window re-read each day (one KIS call, ≤ 100 rows).
 * ETF·ETN을 뺀 상장 종목의 수정주가 일봉 설정이에요. lookbackDays는 종목마다 보관할 달력 일수(기본 210일 ≈ 140거래일, 30~400),
 * refreshDays는 매번 다시 읽는 최근 달력 일수(기본·최대 140일, KIS 한 번 호출에 100행 이하)예요.
 */
@ConfigurationProperties(prefix = "market.adjusted-daily")
public record AdjustedDailySweepProperties(boolean enabled, int lookbackDays, int refreshDays) {
    /**
     * 하는 일: 빈 값에 기본값을 넣고 허용 범위로 잘라요(lookbackDays 30~4000, 기본 3250 ≈ 2018-01 이후, refreshDays 최대 140).
     * 연구용 일봉은 시장 국면(상승·하락·횡보)이 여러 번 들어가도록 2018년부터 보관해요(2026-10-28, 3년에서 확장).
     */
    public AdjustedDailySweepProperties {
        lookbackDays = lookbackDays <= 0 ? 3250 : Math.min(Math.max(lookbackDays, 30), 4000);
        refreshDays = refreshDays <= 0 ? 140 : Math.min(refreshDays, 140);
    }
}
