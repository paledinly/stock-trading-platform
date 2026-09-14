package com.sunmo.stockplatform.marketwide.domain;

import com.sunmo.stockplatform.quote.domain.StockQuote;
import java.math.BigDecimal;
import java.time.Instant;

/** Nullable market fields: a ranking-only observation must not invent OHLC or change values. */
public record BroadQuoteData(BigDecimal price, BigDecimal changeRate, Long volume, BigDecimal tradingValue,
        BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal tradeStrength, Instant observedAt, String source) {
    public boolean complete() {
        return price != null && price.signum() > 0 && changeRate != null && volume != null && volume >= 0
                && tradingValue != null && tradingValue.signum() >= 0;
    }
    public static BroadQuoteData fromQuote(StockQuote quote, String source) {
        return new BroadQuoteData(quote.currentPrice(), quote.changeRate(), quote.accumulatedVolume(),
                quote.accumulatedTradingValue(), quote.openPrice(), quote.highPrice(), quote.lowPrice(), null,
                quote.quotedAt(), source);
    }
    public static BroadQuoteData fromRanking(BroadCandidate.RankingObservation observation) {
        var row = observation.entry();
        return new BroadQuoteData(row.currentPrice(), row.changeRate(), row.accumulatedVolume(),
                row.accumulatedTradingValue(), row.openPrice(), row.highPrice(), row.lowPrice(), row.tradeStrength(),
                observation.receivedAt(), "RANKING:" + observation.type().name());
    }
    public int availableCoreFields() {
        return (price != null && price.signum() > 0 ? 1 : 0) + (changeRate == null ? 0 : 1)
                + (volume == null ? 0 : 1) + (tradingValue == null ? 0 : 1);
    }
}
