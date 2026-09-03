package com.sunmo.stockplatform.market.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record MarketTick(String stockCode, LocalDate businessDate, Instant occurredAt, BigDecimal price,
        long tradeVolume, long cumulativeVolume, BigDecimal cumulativeTradingValue,
        long sequence, BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice,
        BigDecimal tradeStrength, Long cumulativeSellVolume, Long cumulativeBuyVolume,
        BigDecimal buyRatio, boolean tradingHalted, BigDecimal viStandardPrice,
        BigDecimal turnoverRate) {
    public MarketTick(String stockCode, LocalDate businessDate, Instant occurredAt, BigDecimal price,
            long tradeVolume, long cumulativeVolume, BigDecimal cumulativeTradingValue,
            long sequence) {
        this(stockCode, businessDate, occurredAt, price, tradeVolume, cumulativeVolume, cumulativeTradingValue,
                sequence, null, null, null, null, null, null, null, false, null, null);
    }

    public MarketTick {
        if (stockCode == null || stockCode.isBlank() || price == null || price.signum() <= 0 || tradeVolume < 0
                || cumulativeVolume < 0)
            throw new IllegalArgumentException("Invalid market tick");
    }
}
