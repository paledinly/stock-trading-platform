package com.sunmo.stockplatform.market.application;
import com.sunmo.stockplatform.candle.application.*;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import com.sunmo.stockplatform.market.domain.MarketTick;
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
 private final QuoteStateStore quotes;private final StockRepository stocks;private final StockCandleRepository candles;private final MarketEventGateway events;private final FiveMinuteCandleAggregator aggregator;private final ScannerEngine scanner;private final DetectionPerformanceTracker performance;private final RealtimeDiagnostics diagnostics;
 public MarketDataService(QuoteStateStore quotes,StockRepository stocks,StockCandleRepository candles,MarketEventGateway events,RealtimeMarketProperties properties,ScannerEngine scanner,DetectionPerformanceTracker performance,RealtimeDiagnostics diagnostics){this.quotes=quotes;this.stocks=stocks;this.candles=candles;this.events=events;this.aggregator=new FiveMinuteCandleAggregator(properties.candleWatermark());this.scanner=scanner;this.performance=performance;this.diagnostics=diagnostics;}
 @Transactional public void onTick(MarketTick tick){quotes.put(tick);events.publish("quote.updated",Map.of("stockCode",tick.stockCode(),"price",tick.price().toPlainString(),"cumulativeVolume",Long.toString(tick.cumulativeVolume()),"cumulativeTradingValue",tick.cumulativeTradingValue()==null?"0":tick.cumulativeTradingValue().toPlainString(),"occurredAt",tick.occurredAt().toString()));for(CandleSnapshot candle:aggregator.accept(tick))handle(candle);performance.onTick(tick);}
 @Scheduled(fixedDelay=1000) @Transactional public void closeExpired(){aggregator.flush(Instant.now()).forEach(this::handle);}
 private void handle(CandleSnapshot snapshot){if(snapshot.finalCandle())persist(snapshot);scanner.evaluate(snapshot);events.publish(snapshot.finalCandle()?"candle.5m.closed":"candle.5m.updated",payload(snapshot));}
 private void persist(CandleSnapshot c){var stock=stocks.findByStockCodeAndActiveTrue(c.stockCode()).orElse(null);if(stock==null)return;StockCandle entity=candles.findByStockIdAndTimeframeAndStartTime(stock.getId(),"5M",c.startTime()).orElseGet(()->new StockCandle(stock,c.startTime(),c.open(),c.high(),c.low(),c.close(),c.volume(),c.tradingValue(),true,c.revision()));entity.revise(c.open(),c.high(),c.low(),c.close(),c.volume(),c.tradingValue(),true,c.revision());candles.save(entity);diagnostics.candlePersisted(c.startTime());}
 private Map<String,Object> payload(CandleSnapshot c){Map<String,Object> p=new LinkedHashMap<>();p.put("stockCode",c.stockCode());p.put("timeframe","5M");p.put("startTime",c.startTime().toString());p.put("open",c.open().toPlainString());p.put("high",c.high().toPlainString());p.put("low",c.low().toPlainString());p.put("close",c.close().toPlainString());p.put("volume",Long.toString(c.volume()));p.put("tradingValue",c.tradingValue().toPlainString());p.put("final",c.finalCandle());p.put("revision",c.revision());return p;}
}
