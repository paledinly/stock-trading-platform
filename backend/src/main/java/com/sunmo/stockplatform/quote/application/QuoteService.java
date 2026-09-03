package com.sunmo.stockplatform.quote.application;

import com.sunmo.stockplatform.market.application.QuoteStateStore;
import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.quote.domain.StockQuote;
import com.sunmo.stockplatform.stock.application.StockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class QuoteService {
    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final StockService stockService;
    private final QuoteProvider quoteProvider;
    private final QuoteStateStore quoteStateStore;
    private final RealtimeSubscriptionRegistry subscriptions;
    private final Map<String, StockQuote> snapshots = new ConcurrentHashMap<>();

    public QuoteService(StockService stockService, QuoteProvider quoteProvider, QuoteStateStore quoteStateStore,
            RealtimeSubscriptionRegistry subscriptions) {
        this.stockService = stockService;
        this.quoteProvider = quoteProvider;
        this.quoteStateStore = quoteStateStore;
        this.subscriptions = subscriptions;
    }

    public StockQuote getQuote(String stockCode) {
        var stock = stockService.getByCode(stockCode);
        try {
            subscriptions.add(stockCode);
        } catch (IllegalStateException error) {
            log.warn("Quote for {} will use REST without realtime subscription: {}", stockCode, error.getMessage());
        }
        MarketTick tick = quoteStateStore.get(stockCode).orElse(null);
        StockQuote baseline = snapshots.get(stockCode);
        if (baseline == null || !isToday(baseline.quotedAt())) {
            try {
                baseline = quoteProvider.getQuote(stock);
            } catch (RuntimeException error) {
                if (tick == null)
                    throw error;
                log.warn("Using realtime-only quote for {} because baseline quote failed: {}",
                        stockCode, error.getMessage());
                baseline = fromTick(stockCode, stock.getStockName(), stock.getMarket().name(), tick);
            }
        }
        StockQuote result = tick == null ? baseline : merge(baseline, tick);
        snapshots.put(stockCode, result);
        return result;
    }

    static StockQuote merge(StockQuote previous, MarketTick tick) {
        if (!tick.occurredAt().isAfter(previous.quotedAt()))
            return previous;
        BigDecimal current = tick.price();
        BigDecimal previousClose = previous.currentPrice().subtract(previous.change());
        BigDecimal change = current.subtract(previousClose);
        BigDecimal changeRate = previousClose.signum() == 0 ? BigDecimal.ZERO
                : change.divide(previousClose, 8, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).setScale(6, RoundingMode.HALF_UP);
        BigDecimal open = positiveOr(previous.openPrice(), current);
        BigDecimal high = positiveOr(previous.highPrice(), current).max(current);
        BigDecimal low = positiveOr(previous.lowPrice(), current).min(current);
        long volume = Math.max(previous.accumulatedVolume(), tick.cumulativeVolume());
        BigDecimal tradingValue = max(previous.accumulatedTradingValue(), tick.cumulativeTradingValue());
        Instant quotedAt = tick.occurredAt().isAfter(previous.quotedAt()) ? tick.occurredAt() : previous.quotedAt();
        return new StockQuote(previous.stockCode(), previous.stockName(), previous.market(), current, change,
                changeRate,
                open, high, low, volume, tradingValue, quotedAt);
    }

    private static StockQuote fromTick(String code, String name, String market, MarketTick tick) {
        return new StockQuote(code, name, market, tick.price(), BigDecimal.ZERO, BigDecimal.ZERO,
                tick.price(), tick.price(), tick.price(), tick.cumulativeVolume(), tick.cumulativeTradingValue(),
                tick.occurredAt());
    }

    private static boolean isToday(Instant timestamp) {
        return timestamp != null && timestamp.atZone(SEOUL).toLocalDate().equals(LocalDate.now(SEOUL));
    }

    private static BigDecimal positiveOr(BigDecimal value, BigDecimal fallback) {
        return value != null && value.signum() > 0 ? value : fallback;
    }

    private static BigDecimal max(BigDecimal left, BigDecimal right) {
        if (left == null)
            return right == null ? BigDecimal.ZERO : right;
        if (right == null)
            return left;
        return left.max(right);
    }
}
