/*
 * 장마감 추천용 일봉 준비 설정값(closing.daily-backfill.*)을 담아요.
 * DailyCandleBackfillService가 한 번에 몇 종목까지, 며칠치를, 얼마 간격으로 다시 받을지 정할 때 써요.
 */
package com.sunmo.stockplatform.candle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * 일봉 준비 규칙이에요. maxStocks: 한 번에 KIS로 받을 최대 종목 수(기본 40, 최대 50),
 * lookbackDays: 받을 달력 일수(기본 120, 최대 140), refreshInterval: 같은 종목 재시도 최소 간격(기본 1시간)이에요.
 */
@ConfigurationProperties(prefix = "closing.daily-backfill")
public record DailyCandleBackfillProperties(boolean enabled, int maxStocks, int lookbackDays,
        Duration refreshInterval) {
    /** 하는 일: 빈 값에 기본값을 넣고 상한을 적용해요. */
    public DailyCandleBackfillProperties {
        maxStocks = maxStocks <= 0 ? 40 : Math.min(maxStocks, 50);
        lookbackDays = lookbackDays <= 0 ? 120 : Math.min(lookbackDays, 140);
        refreshInterval = refreshInterval == null ? Duration.ofHours(1) : refreshInterval;
    }
}
