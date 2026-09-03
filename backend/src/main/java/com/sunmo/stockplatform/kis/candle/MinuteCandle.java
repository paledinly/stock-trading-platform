package com.sunmo.stockplatform.kis.candle;

import java.math.BigDecimal;
import java.time.Instant;

public record MinuteCandle(Instant startTime, BigDecimal open, BigDecimal high, BigDecimal low,
        BigDecimal close, long volume, BigDecimal cumulativeTradingValue) {
}
