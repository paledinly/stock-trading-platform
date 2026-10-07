package com.sunmo.stockplatform.market.application;

import com.sunmo.stockplatform.candle.application.*;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.market.feature.application.MarketFeatureEngine;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import com.sunmo.stockplatform.scanner.application.ScannerEngine;
import com.sunmo.stockplatform.analytics.application.DetectionPerformanceTracker;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class MarketDataService {
    private final QuoteStateStore quotes;
    private final StockRepository stocks;
    private final StockCandleRepository candles;
    private final MarketEventGateway events;
    private final FiveMinuteCandleAggregator aggregator;
    private final ScannerEngine scanner;
    private final DetectionPerformanceTracker performance;
    private final RealtimeDiagnostics diagnostics;
    private final MarketFeatureEngine features;
    private final org.springframework.context.ApplicationEventPublisher publisher;
    private final org.springframework.transaction.support.TransactionTemplate transaction;

    public MarketDataService(QuoteStateStore quotes, StockRepository stocks, StockCandleRepository candles,
            MarketEventGateway events, RealtimeMarketProperties properties, ScannerEngine scanner,
            DetectionPerformanceTracker performance, RealtimeDiagnostics diagnostics, MarketFeatureEngine features,
            org.springframework.context.ApplicationEventPublisher publisher,
            org.springframework.transaction.PlatformTransactionManager transactions) {
        this.transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        this.quotes = quotes;
        this.stocks = stocks;
        this.candles = candles;
        this.events = events;
        this.aggregator = new FiveMinuteCandleAggregator(properties.candleWatermark());
        this.scanner = scanner;
        this.performance = performance;
        this.diagnostics = diagnostics;
        this.features = features;
        this.publisher = publisher;
    }

    public void onTick(MarketTick tick) {
        onTick(tick, Instant.now(), false);
    }

    public long onTick(MarketTick tick, Instant receivedAt, boolean dataLoss) {
        long bodyStart = System.nanoTime();
        try {
        var feature = features.onTick(tick);
        long redisStart = System.nanoTime();
        try { quotes.put(tick); } finally { diagnostics.timing("redisWrite", System.nanoTime() - redisStart); }
        long sseStart = System.nanoTime();
        try {
        events.publish("quote.updated",
                Map.of("stockCode", tick.stockCode(), "price", tick.price().toPlainString(), "cumulativeVolume",
                        Long.toString(tick.cumulativeVolume()), "cumulativeTradingValue",
                        tick.cumulativeTradingValue() == null ? "0" : tick.cumulativeTradingValue().toPlainString(),
                        "occurredAt", tick.occurredAt().toString()));
        } finally { diagnostics.timing("quoteSse", System.nanoTime() - sseStart); }
        var snapshots = aggregator.accept(tick);
        if (snapshots.stream().anyMatch(CandleSnapshot::finalCandle)) {
            long dbStart = System.nanoTime();
            try { transaction.executeWithoutResult(status -> snapshots.forEach(this::handle)); }
            finally { diagnostics.timing("candleTransaction", System.nanoTime() - dbStart); }
        } else snapshots.forEach(this::handle);
        long performanceStart = System.nanoTime();
        try { performance.onTick(tick); } finally { diagnostics.timing("performanceAndSse", System.nanoTime() - performanceStart); }
        publisher.publishEvent(new ObservedMarketTick(tick, feature, receivedAt, dataLoss));
        return System.nanoTime() - bodyStart;
        } finally { diagnostics.timing("tickBody", System.nanoTime() - bodyStart); }
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void closeExpired() {
        aggregator.flush(Instant.now()).forEach(this::handle);
    }

    private void handle(CandleSnapshot snapshot) {
        long start = System.nanoTime();
        try {
        if (snapshot.finalCandle())
            persist(snapshot);
        if (snapshot.finalCandle()) scanner.evaluate(snapshot);
        else scanner.observeIncomplete(snapshot);
        events.publish(snapshot.finalCandle() ? "candle.5m.closed" : "candle.5m.updated", payload(snapshot));
        } finally { diagnostics.timing("candleScannerAndSse", System.nanoTime() - start); }
    }

    private void persist(CandleSnapshot c) {
        long start = System.nanoTime();
        try {
        var stock = stocks.findByStockCodeAndActiveTrue(c.stockCode()).orElse(null);
        if (stock == null)
            return;
        StockCandle entity = candles.findByStockIdAndTimeframeAndStartTime(stock.getId(), "5M", c.startTime())
                .orElseGet(() -> new StockCandle(stock, c.startTime(), c.open(), c.high(), c.low(), c.close(),
                        c.volume(), c.tradingValue(), true, c.revision()));
        entity.revise(c.open(), c.high(), c.low(), c.close(), c.volume(), c.tradingValue(), true, c.revision());
        candles.save(entity);
        diagnostics.candlePersisted(c.startTime());
        } finally { diagnostics.timing("candleRepository", System.nanoTime() - start); }
    }

    private Map<String, Object> payload(CandleSnapshot c) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("stockCode", c.stockCode());
        p.put("timeframe", "5M");
        p.put("startTime", c.startTime().toString());
        p.put("open", c.open().toPlainString());
        p.put("high", c.high().toPlainString());
        p.put("low", c.low().toPlainString());
        p.put("close", c.close().toPlainString());
        p.put("volume", Long.toString(c.volume()));
        p.put("tradingValue", c.tradingValue().toPlainString());
        p.put("final", c.finalCandle());
        p.put("revision", c.revision());
        return p;
    }
}
