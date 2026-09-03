package com.sunmo.stockplatform.quote.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record StockQuote(
                String stockCode,
                String stockName,
                String market,
                BigDecimal currentPrice,
                BigDecimal change,
                BigDecimal changeRate,
                BigDecimal openPrice,
                BigDecimal highPrice,
                BigDecimal lowPrice,
                long accumulatedVolume,
                BigDecimal accumulatedTradingValue,
                Instant quotedAt) {
}
