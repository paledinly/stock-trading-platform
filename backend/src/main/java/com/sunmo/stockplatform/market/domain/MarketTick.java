package com.sunmo.stockplatform.market.domain;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
public record MarketTick(String stockCode, LocalDate businessDate, Instant occurredAt, BigDecimal price,
                         long tradeVolume, long cumulativeVolume, BigDecimal cumulativeTradingValue,
                         long sequence) {
    public MarketTick {
        if (stockCode == null || stockCode.isBlank() || price == null || price.signum() <= 0 || tradeVolume < 0 || cumulativeVolume < 0)
            throw new IllegalArgumentException("Invalid market tick");
    }
}
