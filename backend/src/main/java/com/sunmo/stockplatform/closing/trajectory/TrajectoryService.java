/*
 * Trajectory 연구 수집의 중심 서비스예요. 체결 이벤트를 1분 집계기에 넘기고, 1초마다 확정된 1분 집계와 맥락 관측을 DB에 저장해요.
 * 14:30~15:20 사이 5분 간격 시각에는 그 시각까지의 자료만으로 스냅샷을 계산해 저장하고 [ClosingCandidate] SHADOW 로그를 남겨요.
 * 기본 꺼짐(closing.trajectory.enabled=false)이며, 수집기·컨트롤러가 이 서비스를 사용해요. 매수 추천이나 주문이 아니에요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import com.sunmo.stockplatform.closing.application.MarketSessionHours;

import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.*;

/**
 * 틱을 1분 기록으로 모으고, 정해진 시각마다 스냅샷(그 시각의 특징값 사진)을 찍어 보관하는 서비스예요.
 * 자동 기상 관측소처럼 바람·기온을 계속 받아 1분 기록으로 정리하고, 5분마다 "그 시각까지의 기록만으로" 요약표를 만들어요.
 * 저장이 실패하면 확정 1분 자료는 메모리에 남겨 두고 다음 번에 다시 저장을 시도해요.
 */
@Service
public class TrajectoryService {
    private final TrajectoryProperties policy;
    private final MinuteFeatureAggregator aggregator;
    private final TrajectoryStore store;
    private final StockRepository stocks;
    private final StockCandleRepository candles;
    private final ClosingTradingCalendar calendar;
    private final TransactionTemplate transaction;
    // 맥락 관측(지수·순위·수급) 대기열이에요. 최대 5000개, 넘치면 버리고 개수만 세요.
    private final ArrayBlockingQueue<Context> contexts = new ArrayBlockingQueue<>(5000);
    private final AtomicLong droppedContexts = new AtomicLong();
    // 계산 중 예외가 나서 건너뛴 스냅샷 수와 마지막으로 건너뛴 종목·기준시각이에요(상태 API로 보여요).
    private final AtomicLong skippedSnapshots = new AtomicLong();
    private volatile String lastSkippedSnapshot;
    private String error;
    private final MicrostructureService microstructure;
    /**
     * 하는 일: 의존 객체를 받아 보관하고, 늦은 틱 대기시간(watermark)을 적용한 1분 집계기와 트랜잭션(한 묶음 DB 작업) 도우미를 만들어요.
     * 받는 것: 설정, 저장소, 종목·봉 레포지토리, 거래일 달력, 트랜잭션 관리자, 미세구조 서비스예요. 돌려주는 것: 서비스 객체예요.
     */
    public TrajectoryService(TrajectoryProperties policy, TrajectoryStore store, StockRepository stocks,
            StockCandleRepository candles, ClosingTradingCalendar calendar, PlatformTransactionManager transactions, MicrostructureService microstructure) {
        this.policy = policy; this.store = store; this.stocks = stocks; this.candles = candles; this.calendar = calendar;
        aggregator = new MinuteFeatureAggregator(policy.watermark()); transaction = new TransactionTemplate(transactions);
        this.microstructure = microstructure;
    }
    /**
     * 하는 일: 체결 틱 이벤트를 받아 거래일이고 수집이 켜져 있으면 1분 집계기에 넘겨요.
     * 체결을 저장한 트랜잭션이 커밋된 뒤에 받아요(트랜잭션 밖 이벤트도 받음). 받는 것: ObservedMarketTick. 돌려주는 것: 없어요.
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void receive(ObservedMarketTick event) {
        if (policy.enabled() && calendar.isTradingDay(event.tick().businessDate())) aggregator.accept(event);
    }
    /** 하는 일: 맥락 관측을 대기열에 넣어요. 가득 차면 버리고 droppedContexts를 올려요. 받는 것: Context. 돌려주는 것: 없어요. */
    public void context(Context row) { if (policy.enabled() && !contexts.offer(row)) droppedContexts.incrementAndGet(); }

    /**
     * 하는 일: (기본 1초마다) 미세구조 자료를 먼저 내보내고, 확정된 1분 집계와 대기 중인 맥락 관측을 한 트랜잭션으로 저장해요.
     * 그중 분석 구간·스냅샷 간격에 맞는 분이 끝나는 시각(cutoff)에는 스냅샷을 계산해 저장해요.
     * 받는 것: 없어요. 돌려주는 것: 없어요. 실패하면 맥락은 대기열에 되돌리고, 1분 집계는 확인 처리하지 않아 다음에 다시 저장해요.
     */
    @Scheduled(fixedDelayString = "${closing.trajectory.flush-interval:1s}", scheduler = "trajectoryScheduler")
    public synchronized void flush() {
        if (!policy.enabled()) return;
        microstructure.flush();
        Instant now = calendar.now();
        List<Minute> ready = aggregator.ready(now);
        List<Context> batch = new ArrayList<>(); contexts.drainTo(batch);
        try {
            transaction.executeWithoutResult(status -> {
                batch.forEach(store::context); ready.forEach(store::minute);
                for (Minute row : ready) {
                    // 분 시작+60초가 스냅샷 기준시각(cutoff)이에요. 분석 구간 밖이거나 간격(기본 5분)에 안 맞으면 건너뛰어요.
                    Instant cutoff = row.start().plusSeconds(60);
                    // 한 종목의 스냅샷 계산·저장이 예외를 던져도 그 종목만 건너뛰고(개수·로그 기록) 나머지 종목과
                    // 1분 집계·맥락 저장은 그대로 커밋해요. 같은 자료로 매번 실패해도 묶음 전체가 막히지 않아요.
                    // 단, DB 자체가 트랜잭션을 깨뜨리는 SQL 오류(PostgreSQL의 aborted transaction)는 예전처럼 묶음 전체 실패로 남아요.
                    try {
                        var local = cutoff.atZone(ClosingTradingCalendar.ZONE);
                        var regular = MarketSessionHours.regularTime(cutoff);
                        if (regular.isBefore(policy.analysisStart()) || regular.isAfter(policy.analysisEnd())
                                || Duration.between(policy.analysisStart(), regular).toMinutes() % policy.snapshotMinutes() != 0) continue;
                        var stock = stocks.findByStockCode(row.symbol()).orElse(null);
                        if (stock == null) continue;
                        // 당일 이전의 확정 일봉을 최대 61개 가져와요(연속성·수신시각 검사는 availableDaily에서 해요).
                        var daily = candles.findTop61ByStockIdAndTimeframeAndStartTimeLessThanEqualAndFinalCandleTrueOrderByStartTimeDesc(
                                stock.getId(), "1D", local.toLocalDate().atStartOfDay(ClosingTradingCalendar.ZONE).toInstant().minusSeconds(1));
                        // Delayed finalization never fabricates historical availability.
                        Snapshot snapshot = TrajectoryFeatures.calculate(row.symbol(), stock.getMarket().name(), cutoff, now,
                                store.minutes(row.symbol(), calendar.open(local.toLocalDate()), cutoff),
                                store.contexts(row.symbol(), stock.getMarket().name(), calendar.open(local.toLocalDate()), cutoff),
                                availableDaily(daily, local.toLocalDate(), now), policy);
                        // 미세구조 자료를 덧붙이고 계산 완료시각을 찍어요. 이미 같은 스냅샷이 있으면 저장·로그를 생략해요.
                        if (snapshot != null) snapshot = microstructure.enrich(snapshot).completedAt(calendar.now());
                        if (snapshot != null && store.snapshot(snapshot))
                            org.slf4j.LoggerFactory.getLogger(getClass()).info("[ClosingCandidate] symbol={} cutoff={} mode=SHADOW price={} turnover={} execution={} vwap={} patterns={} catalyst=UNKNOWN",
                                    row.symbol(), cutoff, snapshot.price(), snapshot.turnover(), snapshot.execution(), snapshot.vwap(), snapshot.intradayPattern());
                    } catch (RuntimeException failure) {
                        skippedSnapshots.incrementAndGet();
                        lastSkippedSnapshot = row.symbol() + "@" + cutoff;
                        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Trajectory snapshot skipped symbol={} cutoff={} ({})",
                                row.symbol(), cutoff, failure.getClass().getSimpleName());
                    }
                }
            });
            aggregator.acknowledge(ready); error = null;
        } catch (RuntimeException failure) {
            batch.forEach(this::context); error = "TRAJECTORY_PERSIST_FAILED";
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Trajectory persistence failed ({})", failure.getClass().getSimpleName());
        }
    }
    /** 하는 일: 수집 상태(켜짐, SHADOW 모드, 거부 틱 수, 버린·대기 맥락 수, 마지막 오류, 건너뛴 스냅샷 수·마지막 건)를 돌려줘요. 받는 것: 없어요. 돌려주는 것: 상태 Map이에요. */
    public synchronized Map<String, Object> status() {
        return fields("enabled", policy.enabled(), "mode", "SHADOW", "rejectedTicks", aggregator.rejected(),
                "droppedContexts", droppedContexts.get(), "pendingContexts", contexts.size(), "error", error,
                "skippedSnapshots", skippedSnapshots.get(), "lastSkippedSnapshot", lastSkippedSnapshot);
    }
    /**
     * 하는 일: 일봉 중 availableBy 시각 전에 이미 생성·수정이 끝난 확정봉만 남기고, 직전 거래일부터 거꾸로 하루도 빠짐없이 이어지는 부분만 골라요.
     * 중간에 거래일이 비면 거기서 멈춰요(늦게 들어온 일봉이 과거 평가에 섞이지 않게 하려는 것).
     * 받는 것: 일봉 목록, 기준 날짜, 사용 가능 마감 시각이에요. 돌려주는 것: 최신순 연속 일봉 목록이에요.
     */
    private List<com.sunmo.stockplatform.candle.domain.StockCandle> availableDaily(
            List<com.sunmo.stockplatform.candle.domain.StockCandle> rows, LocalDate date, Instant availableBy) {
        var valid = rows.stream().filter(c -> c.isFinalCandle() && c.getCreatedAt() != null && c.getUpdatedAt() != null
                        && !c.getCreatedAt().isAfter(availableBy) && !c.getUpdatedAt().isAfter(availableBy))
                .sorted(Comparator.comparing(com.sunmo.stockplatform.candle.domain.StockCandle::getStartTime).reversed()).toList();
        List<com.sunmo.stockplatform.candle.domain.StockCandle> result = new ArrayList<>();
        LocalDate expected = calendar.previousTradingDay(date);
        for (var candle : valid) {
            if (!candle.getStartTime().atZone(ClosingTradingCalendar.ZONE).toLocalDate().equals(expected)) break;
            result.add(candle); expected = calendar.previousTradingDay(expected);
        }
        return result;
    }
}
