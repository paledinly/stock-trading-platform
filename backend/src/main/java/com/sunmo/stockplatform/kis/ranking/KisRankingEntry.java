package com.sunmo.stockplatform.kis.ranking;

import java.math.BigDecimal;

public record KisRankingEntry(String stockCode, String stockName, int rank, BigDecimal currentPrice,
        BigDecimal changeRate, long accumulatedVolume, BigDecimal accumulatedTradingValue,
        BigDecimal tradeStrength) {
}
