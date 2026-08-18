package com.sunmo.stockplatform.quote.application;

import com.sunmo.stockplatform.quote.domain.StockQuote;
import com.sunmo.stockplatform.market.application.QuoteStateStore;
import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.stock.application.StockService;
import org.springframework.stereotype.Service;

@Service
public class QuoteService {
    private final StockService stockService;
    private final QuoteProvider quoteProvider;
    private final QuoteStateStore quoteStateStore;
    private final RealtimeSubscriptionRegistry subscriptions;

    public QuoteService(StockService stockService, QuoteProvider quoteProvider, QuoteStateStore quoteStateStore, RealtimeSubscriptionRegistry subscriptions) {
        this.stockService = stockService;
        this.quoteProvider = quoteProvider;
        this.quoteStateStore = quoteStateStore;
        this.subscriptions = subscriptions;
    }

    public StockQuote getQuote(String stockCode) {
        var stock = stockService.getByCode(stockCode);
        subscriptions.add(stockCode);
        return quoteStateStore.get(stockCode)
                .map(tick -> new StockQuote(stockCode, stock.getStockName(), stock.getMarket().name(), tick.price(),
                        java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, tick.price(), tick.price(), tick.price(),
                        tick.cumulativeVolume(), tick.cumulativeTradingValue(), tick.occurredAt()))
                .orElseGet(() -> quoteProvider.getQuote(stock));
    }
}

