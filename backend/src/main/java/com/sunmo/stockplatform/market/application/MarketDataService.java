/*
 * 실시간 틱(체결 한 건) 하나가 들어왔을 때 해야 할 일을 순서대로 처리하는 중심 서비스예요.
 * Feature 계산 → 최신 시세 저장 → 시세 SSE 발행 → 5분봉 집계 → 확정봉 저장·Scanner 평가 → 성과 추적 → 관측 이벤트 발행 순으로 진행해요.
 * KIS WebSocket 처리 작업자(kis/websocket의 RealtimeTickProcessor, KisRealtimeClient)가 틱마다 onTick을 불러요.
 */
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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 실시간 틱 처리의 조립 라인 반장 같은 클래스예요.
 * 공장 컨베이어처럼 틱 하나가 들어오면 각 작업대(Feature, 시세판, SSE, 봉 집계, Scanner, 성과 추적)에 차례로 넘겨요.
 * 각 단계의 걸린 시간은 RealtimeDiagnostics에 기록해 어디서 느려지는지 볼 수 있게 해요.
 * 일반 틱은 DB 트랜잭션(여러 DB 작업을 한 묶음으로 성공/실패시키는 단위) 없이 처리하고, 확정봉이 생길 때만 트랜잭션을 열어요.
 */
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
    // 봉 집계·확정봉 저장·Scanner 평가·봉 SSE 구간을 한 번에 한 스레드만 지나가게 하는 잠금이에요.
    // 틱 작업자 스레드(onTick)와 스케줄러 스레드(closeExpired)가 같은 잠금을 써서 Scanner가 동시에 돌지 않아요.
    private final ReentrantLock candleLock = new ReentrantLock();

    /**
     * 하는 일: 틱 처리에 필요한 부품을 주입받고, 5분봉 집계기와 트랜잭션 템플릿을 직접 만들어요.
     * 받는 것: 시세 저장소, 종목·봉 레포지토리(DB 테이블을 읽고 쓰는 창구), SSE 게이트웨이, 실시간 설정(봉 확정 watermark),
     * Scanner, 성과 추적기, 진단, Feature 엔진, 스프링 이벤트 발행기, 트랜잭션 관리자.
     */
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

    /**
     * 하는 일: 수신시각을 지금으로, 데이터 손실 없음으로 보고 틱을 처리해요.
     * 받는 것: 틱. 돌려주는 것: 없음.
     */
    public void onTick(MarketTick tick) {
        onTick(tick, Instant.now(), false);
    }

    /**
     * 하는 일: 틱 하나를 모든 처리 단계에 차례로 통과시켜요.
     * 받는 것: 틱, 서버 수신시각, 이 틱 앞에서 연속성이 끊겼는지(dataLoss).
     * 돌려주는 것: 이 메서드 본문 처리에 걸린 시간(나노초).
     */
    public long onTick(MarketTick tick, Instant receivedAt, boolean dataLoss) {
        long bodyStart = System.nanoTime();
        try {
        // 1) Feature 계산과 최신 시세 저장(Redis 포함), 브라우저용 시세 SSE 발행
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
        // 2) 5분봉(5분 동안의 시가·고가·저가·종가·거래량 묶음) 집계.
        //    확정봉이 섞여 있을 때만 저장·평가를 한 트랜잭션으로 묶고, 진행 중 봉만 있으면 DB 없이 처리해요.
        //    closeExpired와 같은 잠금 안에서 집계부터 처리까지 끝내, 봉 처리 순서가 섞이지 않게 해요.
        candleLock.lock();
        try {
        var snapshots = aggregator.accept(tick);
        if (snapshots.stream().anyMatch(CandleSnapshot::finalCandle)) {
            long dbStart = System.nanoTime();
            try { transaction.executeWithoutResult(status -> snapshots.forEach(this::handle)); }
            finally { diagnostics.timing("candleTransaction", System.nanoTime() - dbStart); }
        } else snapshots.forEach(this::handle);
        } finally { candleLock.unlock(); }
        // 3) 탐지 성과 추적을 갱신하고, 다른 기능이 들을 수 있게 관측 이벤트를 발행해요.
        long performanceStart = System.nanoTime();
        try { performance.onTick(tick); } finally { diagnostics.timing("performanceAndSse", System.nanoTime() - performanceStart); }
        publisher.publishEvent(new ObservedMarketTick(tick, feature, receivedAt, dataLoss));
        return System.nanoTime() - bodyStart;
        } finally { diagnostics.timing("tickBody", System.nanoTime() - bodyStart); }
    }

    /**
     * 하는 일: 1초마다 돌면서 시간이 지나(봉 끝 + watermark) 더 기다릴 필요가 없는 봉을 확정해요.
     * 다음 틱이 오지 않는 종목도 봉이 닫히도록 하는 스케줄러(정해진 주기로 자동 실행되는 작업)예요.
     * 받는 것: 없음. 돌려주는 것: 없음.
     * 봉 확정 전용 스케줄러(candleCloseScheduler) 스레드에서 돌지만 onTick과 같은 candleLock을 잡은 뒤 flush·handle(저장·Scanner 평가·SSE)을 하므로,
     * 틱 작업자 스레드와 동시에 Scanner를 부르지 않아요. 확정된 봉이 있으면 예전 @Transactional과 똑같이
     * 그 묶음 전체를 한 트랜잭션으로 저장·평가하고(예외면 모두 롤백), 확정할 봉이 없으면 트랜잭션을 열지 않아요.
     * 잠금을 먼저 잡고 트랜잭션을 여는 순서는 onTick과 같아서, DB 연결을 쥔 채 잠금을 기다리는 일이 없어요.
     */
    @Scheduled(fixedDelay = 1000, scheduler = "candleCloseScheduler")
    public void closeExpired() {
        closeExpired(Instant.now());
    }

    /**
     * 하는 일: 기준 시각으로 기한이 지난 봉을 확정해 처리해요(시각을 받을 수 있어 테스트하기 쉬워요).
     * 받는 것: 기준 시각. 돌려주는 것: 없음.
     */
    void closeExpired(Instant now) {
        candleLock.lock();
        try {
            List<CandleSnapshot> closed = aggregator.flush(now);
            if (closed.isEmpty())
                return;
            transaction.executeWithoutResult(status -> closed.forEach(this::handle));
        } finally {
            candleLock.unlock();
        }
    }

    /**
     * 하는 일: 봉 스냅샷 하나를 처리해요. 확정봉이면 DB에 저장하고 Scanner로 평가하며, 진행 중 봉이면 Scanner에 관찰만 알려요.
     * 끝으로 브라우저에 candle.5m.closed(확정) 또는 candle.5m.updated(진행 중) 이벤트를 보내요.
     * 받는 것: 봉 스냅샷. 돌려주는 것: 없음.
     */
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

    /**
     * 하는 일: 확정 5분봉을 stock_candle 테이블에 저장해요. 같은 시작시각의 행이 있으면 실시간 값으로 고쳐 써요.
     * 받는 것: 확정봉 스냅샷. 돌려주는 것: 없음. 활성 종목이 아니면 저장하지 않아요.
     */
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

    /**
     * 하는 일: 봉 스냅샷을 SSE로 보낼 JSON 모양의 Map으로 바꿔요. 금액은 소수 표기가 흔들리지 않게 문자열로 넣어요.
     * 받는 것: 봉 스냅샷. 돌려주는 것: 필드 순서가 유지되는 Map.
     */
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
