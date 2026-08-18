package com.sunmo.stockplatform.kis.quote;

import com.sunmo.stockplatform.quote.domain.StockQuote;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;

@Component
class KisQuoteMapper {
    private final Clock clock;

    KisQuoteMapper() {
        this(Clock.systemUTC());
    }

    KisQuoteMapper(Clock clock) {
        this.clock = clock;
    }

    StockQuote map(Stock stock, KisQuoteResponse.Output output) {
        return new StockQuote(stock.getStockCode(), stock.getStockName(), stock.getMarket().name(),
                decimal(output.currentPrice()), signedDecimal(output.change(), output.previousDaySign()),
                signedDecimal(output.changeRate(), output.previousDaySign()),
                decimal(output.openPrice()), decimal(output.highPrice()), decimal(output.lowPrice()),
                longValue(output.accumulatedVolume()), decimal(output.accumulatedTradingValue()), clock.instant());
    }

    private BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value.trim());
    }

    private BigDecimal signedDecimal(String value, String signCode) {
        BigDecimal number = decimal(value).abs();
        return "4".equals(signCode) || "5".equals(signCode) ? number.negate() : number;
    }

    private long longValue(String value) {
        return value == null || value.isBlank() ? 0L : Long.parseLong(value.trim());
    }
}
