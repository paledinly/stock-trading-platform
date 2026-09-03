package com.sunmo.stockplatform.candle.application;

import java.math.BigDecimal;
import java.time.Instant;

public record CandleSnapshot(String stockCode, Instant startTime, BigDecimal open, BigDecimal high, BigDecimal low,
        BigDecimal close, long volume, BigDecimal tradingValue, boolean finalCandle, int revision) {
}
