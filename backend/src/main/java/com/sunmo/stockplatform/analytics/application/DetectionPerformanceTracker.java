package com.sunmo.stockplatform.analytics.application;

import com.sunmo.stockplatform.analytics.domain.*;
import com.sunmo.stockplatform.analytics.infrastructure.DetectionPerformanceRepository;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.market.application.*;
import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Service
@Order(30)
public class DetectionPerformanceTracker implements ApplicationRunner {
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private final DetectionPerformanceRepository repository;
    private final StockCandleRepository candles;
    private final QuoteStateStore quotes;
    private final MarketEventGateway events;
    private final RealtimeDiagnostics diagnostics;
    private final ConcurrentMap<String,ConcurrentMap<Long,DetectionPerformance>> byStock=new ConcurrentHashMap<>();
    private final ConcurrentMap<Long,DetectionPerformance> byId=new ConcurrentHashMap<>();
    private final Set<Long> dirty=ConcurrentHashMap.newKeySet();

    public DetectionPerformanceTracker(DetectionPerformanceRepository repository,StockCandleRepository candles,
                                       QuoteStateStore quotes,MarketEventGateway events,RealtimeDiagnostics diagnostics){
        this.repository=repository;this.candles=candles;this.quotes=quotes;this.events=events;this.diagnostics=diagnostics;
    }

    @Override @Transactional
    public void run(ApplicationArguments args){
        LocalDate today=LocalDate.now(SEOUL);Instant now=Instant.now();
        for(DetectionPerformance performance:repository.findWithDetectionByStatus(PerformanceStatus.PENDING)){
            recoverFromStoredCandles(performance);
            LocalDate sessionDate=performance.getDetection().getSessionDate();
            if(sessionDate.isBefore(today)||(sessionDate.equals(today)&&!now.isBefore(sessionClose(today).plus(Duration.ofMinutes(1))))){
                finalizeRecovered(performance,sessionClose(performance.getDetection().getSessionDate()));
                repository.save(performance);
            }else register(performance);
        }
        diagnostics.pendingPerformances(byId.size());
    }

    @Transactional
    public void initialize(ScannerDetection detection){DetectionPerformance performance=repository.save(new DetectionPerformance(detection));register(performance);diagnostics.pendingPerformances(byId.size());}

    public synchronized void onTick(MarketTick tick){
        Map<Long,DetectionPerformance> pending=byStock.get(tick.stockCode());if(pending==null)return;
        for(DetectionPerformance performance:pending.values())if(performance.observe(tick.price(),tick.occurredAt())){dirty.add(performance.getDetectionId());publish(performance,tick.stockCode());}
    }

    @Scheduled(fixedDelayString="${market.performance.flush-interval:1s}") @Transactional
    public synchronized void flushDirty(){List<DetectionPerformance> changed=new ArrayList<>();for(Long id:List.copyOf(dirty))if(dirty.remove(id)){DetectionPerformance value=byId.get(id);if(value!=null)changed.add(value);}if(!changed.isEmpty()){repository.saveAll(changed).forEach(this::register);diagnostics.performanceFlushed(changed.size());}}

    @Scheduled(cron="0 31 15 * * MON-FRI",zone="Asia/Seoul") @Transactional
    public synchronized void finalizeMarketClose(){Instant now=Instant.now();for(DetectionPerformance performance:List.copyOf(byId.values())){recoverFromStoredCandles(performance);String code=performance.getDetection().getStock().getStockCode();var tick=quotes.get(code).orElse(null);performance.finalizeClose(tick==null?lastStoredClose(performance):tick.price(),now);repository.save(performance);unregister(performance);publish(performance,code);}diagnostics.pendingPerformances(byId.size());}

    private void recoverFromStoredCandles(DetectionPerformance performance){ScannerDetection detection=performance.getDetection();Instant safeStart=nextBucket(detection.getDetectedAt());for(StockCandle candle:storedCandles(detection))if(candle.isFinalCandle()&&!candle.getStartTime().isBefore(safeStart))performance.observeRange(candle.getHigh(),candle.getLow(),candle.getClose(),candle.getStartTime().plus(Duration.ofMinutes(5)));}
    private void finalizeRecovered(DetectionPerformance performance,Instant at){recoverFromStoredCandles(performance);performance.finalizeClose(lastStoredClose(performance),at,"STARTUP_RECOVERY");}
    private java.math.BigDecimal lastStoredClose(DetectionPerformance performance){List<StockCandle> stored=storedCandles(performance.getDetection());return stored.isEmpty()?null:stored.getLast().getClose();}
    private List<StockCandle> storedCandles(ScannerDetection detection){return candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(detection.getStock().getId(),"5M",sessionOpen(detection.getSessionDate()),sessionClose(detection.getSessionDate()));}
    private void register(DetectionPerformance performance){Long id=performance.getDetectionId();String code=performance.getDetection().getStock().getStockCode();byId.put(id,performance);byStock.computeIfAbsent(code,ignored->new ConcurrentHashMap<>()).put(id,performance);}
    private void unregister(DetectionPerformance performance){Long id=performance.getDetectionId();String code=performance.getDetection().getStock().getStockCode();dirty.remove(id);byId.remove(id);byStock.computeIfPresent(code,(ignored,values)->{values.remove(id);return values.isEmpty()?null:values;});}
    private void publish(DetectionPerformance performance,String code){events.publish("scanner.performance.updated",Map.of("detectionId",performance.getDetectionId(),"stockCode",code,"status",performance.getStatus().name()));}
    private Instant sessionOpen(LocalDate date){return date.atTime(9,0).atZone(SEOUL).toInstant();}
    private Instant sessionClose(LocalDate date){return date.atTime(15,30).atZone(SEOUL).toInstant();}
    private Instant nextBucket(Instant instant){ZonedDateTime original=instant.atZone(SEOUL);ZonedDateTime time=original.withSecond(0).withNano(0);int remainder=time.getMinute()%5;if(remainder!=0||original.getSecond()!=0||original.getNano()!=0)time=time.plusMinutes(remainder==0?5:5-remainder);return time.toInstant();}
}
