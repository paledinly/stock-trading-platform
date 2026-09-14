package com.sunmo.stockplatform.kis.ranking;

import java.math.BigDecimal;

public record KisRankingEntry(String stockCode, String stockName, int rank, BigDecimal currentPrice,
        BigDecimal changeRate, Long accumulatedVolume, BigDecimal accumulatedTradingValue,
        BigDecimal tradeStrength, BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice) {
    public KisRankingEntry(String code, String name, int rank, BigDecimal price, BigDecimal rate,
            long volume, BigDecimal value, BigDecimal strength) {
        this(code, name, rank, price, rate, volume, value, strength, null, null, null);
    }
}
