/*
 * 스캐너 감지 이후의 가격 흐름(성과)을 실시간으로 따라가며 기록하는 파일이에요.
 * 추적 중인 성과를 메모리에 들고 틱이 올 때마다 갱신하고, 바뀐 것만 1초마다 DB에 저장하며, 15:31:30에 하루치를 확정해요.
 * 서버가 다시 켜지면 저장된 5분봉으로 빠진 구간을 복구해요. 감지를 만드는 스캐너 쪽과 실시간 시세 처리 쪽이 이 클래스를 불러요.
 */
package com.sunmo.stockplatform.analytics.application;

import com.sunmo.stockplatform.closing.application.MarketSessionHours;

import com.sunmo.stockplatform.analytics.domain.*;
import com.sunmo.stockplatform.analytics.infrastructure.DetectionPerformanceRepository;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.market.application.*;
import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 추적 중(PENDING)인 감지 성과를 메모리에 모아 두고 틱·봉·장 마감에 맞춰 갱신하고 저장하는 클래스예요.
 * 달리기 출발 뒤 구간마다 기록을 재는 기록원처럼, 감지된 종목마다 5분·10분·30분·60분·마감 기록을 받아 적고 결승선(15:31:30)에서 기록지를 마감해요.
 * 서버 시작 시(ApplicationRunner) 남아 있던 PENDING 기록을 다시 불러와 이어서 추적하거나 지난 날짜면 바로 확정해요.
 */
@Service
@Order(30)
public class DetectionPerformanceTracker implements ApplicationRunner {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final DetectionPerformanceRepository repository;
    private final StockCandleRepository candles;
    private final QuoteStateStore quotes;
    private final MarketEventGateway events;
    private final RealtimeDiagnostics diagnostics;
    private final TransactionTemplate transaction;
    // 거래일 여부, 현재 시각, 정규장 시작·종료 시각은 모두 ClosingTradingCalendar 정책을 따라요.
    private final ClosingTradingCalendar calendar;
    // 종목코드 → (감지 ID → 성과): 틱이 오면 그 종목의 추적 중인 성과만 빨리 찾기 위한 색인이에요.
    private final ConcurrentMap<String, ConcurrentMap<Long, DetectionPerformance>> byStock = new ConcurrentHashMap<>();
    // 감지 ID → 성과: 추적 중인 전체 목록이에요.
    private final ConcurrentMap<Long, DetectionPerformance> byId = new ConcurrentHashMap<>();
    // 값이 바뀌어 아직 DB에 저장하지 않은 감지 ID 모음이에요.
    private final Set<Long> dirty = ConcurrentHashMap.newKeySet();

    /**
     * 필요한 저장소와 도구를 받아 추적기를 만들어요.
     * 받는 것: 성과 레포지토리(DB 읽기·쓰기 담당 객체), 5분봉 레포지토리, 현재가 저장소, 이벤트 발행기, 진단 기록기,
     * 트랜잭션(여러 DB 작업을 한 묶음으로 처리하는 단위) 관리자, 거래일 달력.
     * 돌려주는 것: 추적기 객체예요.
     */
    public DetectionPerformanceTracker(DetectionPerformanceRepository repository, StockCandleRepository candles,
            QuoteStateStore quotes, MarketEventGateway events, RealtimeDiagnostics diagnostics,
            PlatformTransactionManager transactions, ClosingTradingCalendar calendar) {
        this.repository = repository;
        this.candles = candles;
        this.quotes = quotes;
        this.events = events;
        this.diagnostics = diagnostics;
        this.transaction = new TransactionTemplate(transactions);
        this.calendar = calendar;
    }

    /**
     * 서버 시작 시 한 번 실행돼요. DB에 남은 PENDING 성과를 불러와 저장된 봉으로 복구해요.
     * 지난 날짜이거나 오늘 정규장 마감(달력 기준) 1분 뒤가 지났으면 바로 확정하고, 아니면 메모리에 올려 계속 추적해요.
     * 받는 것: 실행 인자(쓰지 않아요). 돌려주는 것: 없어요.
     */
    @Override
    public void run(ApplicationArguments args) {
        // 장중 재시작이면 이 복구가 도는 동안 1초 저장(flushDirty)이 같은 행을 먼저 저장할 수 있어요.
        // 그때 마지막 커밋이 낙관적 잠금 충돌로 실패해도 서버 기동을 멈추지 않아요(2026-10-15 13:48 기동 실패).
        // 충돌한 행은 이미 추적 목록에 올라가 있어, 다음 저장 때 flushDirty가 DB에서 다시 읽어 봉으로 복구해요.
        try {
            transaction.executeWithoutResult(status -> recoverPending());
        } catch (ObjectOptimisticLockingFailureException stale) {
            org.slf4j.LoggerFactory.getLogger(DetectionPerformanceTracker.class)
                    .warn("Startup performance recovery hit a concurrent update; continuing, rows are reloaded on the next flush: {}",
                            stale.getMessage());
            dirty.addAll(byId.keySet());
        }
        diagnostics.pendingPerformances(byId.size());
    }

    /** 하는 일: PENDING 성과를 불러와 봉으로 복구하고, 지난 세션은 확정·저장, 진행 중이면 추적 목록에 올려요(run의 트랜잭션 안에서 실행). */
    private void recoverPending() {
        LocalDate today = calendar.today();
        Instant now = calendar.now();
        for (DetectionPerformance performance : repository.findWithDetectionByStatus(PerformanceStatus.PENDING)) {
            recoverFromStoredCandles(performance);
            // 세션이 이미 끝났으면 복구 마감, 진행 중이면 실시간 추적 목록에 등록해요.
            LocalDate sessionDate = performance.getDetection().getSessionDate();
            if (sessionDate.isBefore(today)
                    || (sessionDate.equals(today) && !now.isBefore(sessionClose(today).plus(Duration.ofMinutes(1))))) {
                finalizeRecovered(performance, sessionClose(performance.getDetection().getSessionDate()));
                repository.save(performance);
            } else
                register(performance);
        }
    }

    /**
     * 새 감지가 생기면 성과 기록을 만들어 저장하고 추적을 시작해요.
     * 받는 것: 방금 저장된 감지. 돌려주는 것: 없어요.
     */
    @Transactional
    public void initialize(ScannerDetection detection) {
        DetectionPerformance performance = repository.save(new DetectionPerformance(detection));
        register(performance);
        diagnostics.pendingPerformances(byId.size());
    }

    /**
     * 실시간 체결(틱)이 들어오면 그 종목의 추적 중인 성과에 가격을 반영해요.
     * 바뀐 성과는 저장 대기(dirty)로 표시하고 화면 갱신 이벤트를 보내요. DB 저장은 flushDirty가 따로 해요.
     * 받는 것: 체결 틱(종목코드, 가격, 발생 시각). 돌려주는 것: 없어요.
     */
    public synchronized void onTick(MarketTick tick) {
        Map<Long, DetectionPerformance> pending = byStock.get(tick.stockCode());
        if (pending == null)
            return;
        for (DetectionPerformance performance : pending.values())
            if (performance.observe(tick.price(), tick.occurredAt())) {
                dirty.add(performance.getDetectionId());
                publish(performance, tick.stockCode());
            }
    }

    /**
     * Each row is saved in its own transaction so one stale row cannot discard the whole batch. A stale row
     * (e.g. a commit applied while the connection failed) is reloaded and rebuilt from stored candles.
     *
     * 스케줄러(정해진 간격마다 자동 실행해 주는 장치)가 기본 1초마다 불러요. 바뀐 성과만 골라 한 건씩 따로 저장해요.
     * 한 건이 낡은 값이라 충돌하면 DB에서 다시 읽고 봉으로 복구한 뒤 다음 번에 다시 저장해요.
     * 받는 것: 없어요. 돌려주는 것: 없어요(저장 건수는 진단 기록에 남겨요).
     */
    @Scheduled(fixedDelayString = "${market.performance.flush-interval:1s}")
    public synchronized void flushDirty() {
        List<DetectionPerformance> changed = new ArrayList<>();
        for (Long id : List.copyOf(dirty))
            if (dirty.remove(id)) {
                DetectionPerformance value = byId.get(id);
                if (value != null)
                    changed.add(value);
            }
        int saved = 0;
        for (DetectionPerformance value : changed) {
            try {
                transaction.executeWithoutResult(status -> register(repository.save(value)));
                saved++;
            } catch (ObjectOptimisticLockingFailureException stale) {
                transaction.execute(status -> reloadStale(value)).ifPresent(fresh -> dirty.add(fresh.getDetectionId()));
            }
        }
        if (saved > 0)
            diagnostics.performanceFlushed(saved);
    }

    /**
     * 평일 15:31:30(서울, 랜덤엔드 종가 체결 15:30:30 + 수신 여유)에 추적 중인 모든 성과를 마감 가격으로 확정해요.
     * ClosingTradingCalendar가 오늘을 거래일이 아니라고 하면(평일 휴장일) 아무것도 확정하지 않고 넘어가요.
     * 충돌이 나면 DB에서 다시 읽어 확정을 한 번 더 시도해요.
     * 받는 것: 없어요. 돌려주는 것: 없어요.
     */
    @Scheduled(cron = "30 31 15,16 * * MON-FRI", zone = "Asia/Seoul")
    public synchronized void finalizeMarketClose() {
        // 평소 15:31:30과 한 시간 뒤에 깨어나, 그날 마감(MarketSessionHours)에 맞는 쪽만 확정해요.
        if (!calendar.isTradingDay(calendar.today())
                || !MarketSessionHours.firesAt(calendar.now(), java.time.LocalTime.of(15, 31)))
            return;
        Instant now = calendar.now();
        for (DetectionPerformance performance : List.copyOf(byId.values())) {
            String code = performance.getDetection().getStock().getStockCode();
            try {
                transaction.executeWithoutResult(status -> finalizeAndSave(performance, code, now));
            } catch (ObjectOptimisticLockingFailureException stale) {
                transaction.executeWithoutResult(status -> reloadStale(performance)
                        .ifPresent(fresh -> finalizeAndSave(fresh, code, now)));
            }
        }
        diagnostics.pendingPerformances(byId.size());
    }

    /**
     * 성과 하나를 봉으로 마저 채우고 마감 가격으로 확정한 뒤 저장하고 추적 목록에서 빼요.
     * 받는 것: 성과, 종목코드, 확정 시각. 돌려주는 것: 없어요.
     */
    private void finalizeAndSave(DetectionPerformance performance, String code, Instant now) {
        recoverFromStoredCandles(performance);
        // 마감 가격은 메모리의 최신 현재가를 우선 쓰지만, 그 체결이 감지한 세션 날짜에 일어났고
        // 정규장 마감 1분 뒤를 넘지 않은 경우에만 써요. 그렇지 않으면(지난 날 가격 등) 저장된 마지막 확정 5분봉 종가를 써요.
        LocalDate sessionDate = performance.getDetection().getSessionDate();
        var tick = quotes.get(code).filter(value -> sessionTick(value, sessionDate)).orElse(null);
        performance.finalizeClose(tick == null ? lastStoredClose(performance) : tick.price(), now);
        DetectionPerformance saved = repository.save(performance);
        unregister(saved);
        publish(saved, code);
    }

    /**
     * 하는 일: 현재가 틱을 그 세션의 마감 가격으로 써도 되는지 봐요.
     * 받는 것: 틱, 세션 날짜. 돌려주는 것: 체결 시각이 세션 날짜(서울)이고 정규장 마감 + 1분을 넘지 않으면 true예요.
     */
    private boolean sessionTick(MarketTick tick, LocalDate sessionDate) {
        return tick.occurredAt().atZone(SEOUL).toLocalDate().equals(sessionDate)
                && !tick.occurredAt().isAfter(sessionClose(sessionDate).plus(Duration.ofMinutes(1)));
    }

    /**
     * Replaces a stale in-memory row with the stored one; empty when it is gone or no longer pending.
     *
     * 메모리의 낡은 성과를 DB의 최신 값으로 바꾸고 봉으로 다시 채워요.
     * 받는 것: 저장 충돌이 난 성과. 돌려주는 것: 아직 PENDING인 최신 성과, 없거나 이미 확정됐으면 빈 값이에요.
     */
    private Optional<DetectionPerformance> reloadStale(DetectionPerformance stale) {
        Long id = stale.getDetectionId();
        diagnostics.error("Detection performance " + id + " was stale and reloaded from the database");
        Optional<DetectionPerformance> fresh = repository.findWithDetectionById(id)
                .filter(row -> row.getStatus() == PerformanceStatus.PENDING);
        if (fresh.isEmpty()) {
            unregister(stale);
            return Optional.empty();
        }
        recoverFromStoredCandles(fresh.get());
        register(fresh.get());
        return fresh;
    }

    /**
     * 저장된 확정 5분봉으로 성과의 빈 구간을 채워요.
     * 감지 시각 이후에 시작한 봉만 쓰고, 관측 시각은 봉이 끝난 시각(시작 + 5분)으로 넣어요.
     * 받는 것: 복구할 성과. 돌려주는 것: 없어요.
     */
    private void recoverFromStoredCandles(DetectionPerformance performance) {
        ScannerDetection detection = performance.getDetection();
        // 감지가 봉 중간에 났다면 그 봉에는 감지 전 가격이 섞여 있으니 다음 봉부터 써요.
        Instant safeStart = nextBucket(detection.getDetectedAt());
        for (StockCandle candle : storedCandles(detection))
            if (candle.isFinalCandle() && !candle.getStartTime().isBefore(safeStart))
                performance.observeRange(candle.getHigh(), candle.getLow(), candle.getClose(),
                        candle.getStartTime().plus(Duration.ofMinutes(5)));
    }

    /**
     * 서버 재시작 때 이미 끝난 세션의 성과를 봉만으로 확정해요. 사유는 STARTUP_RECOVERY예요.
     * 받는 것: 성과와 확정 시각(그 날 15:30). 돌려주는 것: 없어요.
     */
    private void finalizeRecovered(DetectionPerformance performance, Instant at) {
        recoverFromStoredCandles(performance);
        performance.finalizeClose(lastStoredClose(performance), at, "STARTUP_RECOVERY");
    }

    /**
     * 하는 일: 감지한 날의 저장된 5분봉 중 마지막 확정봉의 종가를 찾아요(recoverFromStoredCandles처럼 확정봉만 써요).
     * 받는 것: 성과. 돌려주는 것: 마지막 확정봉 종가, 확정봉이 없으면 null이에요.
     */
    private java.math.BigDecimal lastStoredClose(DetectionPerformance performance) {
        List<StockCandle> stored = storedCandles(performance.getDetection());
        for (int i = stored.size() - 1; i >= 0; i--)
            if (stored.get(i).isFinalCandle())
                return stored.get(i).getClose();
        return null;
    }

    /**
     * 하는 일: 감지한 날 정규장 시작~종료(달력 기준) 사이의 저장된 5분봉을 시간순으로 불러와요.
     * 받는 것: 감지. 돌려주는 것: 5분봉 목록이에요.
     */
    private List<StockCandle> storedCandles(ScannerDetection detection) {
        return candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                detection.getStock().getId(), "5M", sessionOpen(detection.getSessionDate()),
                sessionClose(detection.getSessionDate()));
    }

    /** 하는 일: 성과를 추적 목록(ID별·종목별)에 올리거나 최신 객체로 바꿔 넣어요. 받는 것: 성과. */
    private void register(DetectionPerformance performance) {
        Long id = performance.getDetectionId();
        String code = performance.getDetection().getStock().getStockCode();
        byId.put(id, performance);
        byStock.computeIfAbsent(code, ignored -> new ConcurrentHashMap<>()).put(id, performance);
    }

    /** 하는 일: 성과를 추적 목록과 저장 대기에서 빼요. 종목에 남은 성과가 없으면 종목 칸도 지워요. 받는 것: 성과. */
    private void unregister(DetectionPerformance performance) {
        Long id = performance.getDetectionId();
        String code = performance.getDetection().getStock().getStockCode();
        dirty.remove(id);
        byId.remove(id);
        byStock.computeIfPresent(code, (ignored, values) -> {
            values.remove(id);
            return values.isEmpty() ? null : values;
        });
    }

    /** 하는 일: 화면이 새로 그리도록 "scanner.performance.updated" 이벤트(감지 ID, 종목코드, 상태)를 보내요. */
    private void publish(DetectionPerformance performance, String code) {
        events.publish("scanner.performance.updated", Map.of("detectionId", performance.getDetectionId(), "stockCode",
                code, "status", performance.getStatus().name()));
    }

    /** 하는 일: 그 날짜의 정규장 시작 시각을 ClosingTradingCalendar.open에서 받아 돌려줘요. */
    private Instant sessionOpen(LocalDate date) {
        return calendar.open(date);
    }

    /**
     * 하는 일: 그 날짜의 정규장 마감 시각을 ClosingTradingCalendar.close에서 받아 돌려줘요.
     * 개장 지연·단축 장 같은 특별한 날은 달력 정책이 정해요.
     */
    private Instant sessionClose(LocalDate date) {
        return calendar.close(date);
    }

    /**
     * 하는 일: 주어진 시각 이후 처음 시작하는 5분봉 경계 시각을 구해요(정확히 경계면 그대로).
     * 받는 것: 시각. 돌려주는 것: 다음 5분 경계 시각이에요. 예) 10:02:30 → 10:05, 10:05:00 → 10:05.
     */
    private Instant nextBucket(Instant instant) {
        ZonedDateTime original = instant.atZone(SEOUL);
        ZonedDateTime time = original.withSecond(0).withNano(0);
        int remainder = time.getMinute() % 5;
        if (remainder != 0 || original.getSecond() != 0 || original.getNano() != 0)
            time = time.plusMinutes(remainder == 0 ? 5 : 5 - remainder);
        return time.toInstant();
    }
}
