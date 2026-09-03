package com.sunmo.stockplatform.market.feature.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record MarketFeatureSnapshot(
        String stockCode,
        LocalDate businessDate,
        Instant occurredAt,
        BigDecimal price,
        long cumulativeVolume,
        BigDecimal cumulativeTradingValue,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal vwap,
        BigDecimal vwapDistanceRate,
        BigDecimal vwapSlopeRate,
        BigDecimal volumeRatio,
        BigDecimal turnoverRatio,
        BigDecimal tradeStrength,
        long buyVolumeDelta,
        long sellVolumeDelta,
        BigDecimal buyRatio,
        BigDecimal dayHighDistanceRate,
        boolean tradingHalted,
        BigDecimal viStandardPrice,
        String featureVersion) {
    public static final String VERSION = "market-feature-v1";
}
