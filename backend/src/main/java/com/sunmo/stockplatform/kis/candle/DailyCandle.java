package com.sunmo.stockplatform.kis.candle;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyCandle(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low,
        BigDecimal close, long volume, BigDecimal tradingValue) { }
