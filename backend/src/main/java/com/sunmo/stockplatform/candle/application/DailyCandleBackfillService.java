/*
 * 장마감 추천 계산에 필요한 일봉을 KIS REST(HTTP 주소로 데이터를 요청하는 방식)로 미리 받아 두는 서비스예요.
 * 평일 08:10에 후보 종목 전체를, 14:25·14:35에 그날 ACTIVE 정밀 후보를 우선 준비하며, 평가일 직전 거래일까지의 일봉만 요청해요.
 * 스케줄러가 자동으로 부르고, DailyCandleBackfillController로 수동 실행도 할 수 있어요.
 */
package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.closing.application.MarketSessionHours;

import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.candle.config.DailyCandleBackfillProperties;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.kis.candle.KisDailyCandleClient;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.marketwide.domain.PrecisionSubscriptionSession;
import com.sunmo.stockplatform.marketwide.infrastructure.PrecisionSubscriptionSessionRepository;
import com.sunmo.stockplatform.scanner.infrastructure.ScannerDetectionRepository;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import com.sunmo.stockplatform.watchlist.infrastructure.WatchlistItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 추천 계산 전 일봉 장보기 담당이에요.
 * 요리 전에 재료 목록(후보 종목)을 보고, 냉장고(DB)에 이미 충분히 있는 재료는 건너뛰고 모자란 것만 사 오는 것과 같아요.
 * 한 번에 사 오는 양(maxStocks)과 같은 재료 재구매 간격(refreshInterval)을 제한해 KIS 호출을 아껴요.
 */
@Service
public class DailyCandleBackfillService {
    private static final Logger log = LoggerFactory.getLogger(DailyCandleBackfillService.class);
    private final DailyCandleBackfillProperties settings;
    private final KisProperties kis;
    private final ClosingTradingCalendar calendar;
    private final ScannerDetectionRepository detections;
    private final WatchlistItemRepository watchlist;
    private final StockRepository stocks;
    private final PrecisionSubscriptionSessionRepository precisionSessions;
    private final KisDailyCandleClient client;
    private final DailyCandlePersistence persistence;
    // 지금 실제로 정밀 구독 중인 종목을 알려 주는 실시간 구독 등록부예요(없으면 오늘 세션 기록만 봐요).
    private final RealtimeSubscriptionRegistry registry;
    private final Map<String, Instant> lastAttempt = new ConcurrentHashMap<>();

    /**
     * 하는 일: 설정, KIS 설정, 거래일 달력, 탐지·관심종목·종목·정밀 세션 레포지토리, KIS 일봉 클라이언트, 일봉 저장기를 주입받아요(의존성 주입).
     */
    public DailyCandleBackfillService(DailyCandleBackfillProperties settings, KisProperties kis,
            ClosingTradingCalendar calendar, ScannerDetectionRepository detections,
            WatchlistItemRepository watchlist, StockRepository stocks, KisDailyCandleClient client,
            DailyCandlePersistence persistence, PrecisionSubscriptionSessionRepository precisionSessions) {
        this(settings, kis, calendar, detections, watchlist, stocks, client, persistence, precisionSessions, null);
    }

    /** 위와 같고, 지금 정밀 구독 중인 종목을 알려 주는 실시간 구독 등록부를 함께 받아요. */
    @org.springframework.beans.factory.annotation.Autowired
    public DailyCandleBackfillService(DailyCandleBackfillProperties settings, KisProperties kis,
            ClosingTradingCalendar calendar, ScannerDetectionRepository detections,
            WatchlistItemRepository watchlist, StockRepository stocks, KisDailyCandleClient client,
            DailyCandlePersistence persistence, PrecisionSubscriptionSessionRepository precisionSessions,
            RealtimeSubscriptionRegistry registry) {
        this.registry = registry;
        this.settings = settings;
        this.kis = kis;
        this.calendar = calendar;
        this.detections = detections;
        this.watchlist = watchlist;
        this.stocks = stocks;
        this.precisionSessions = precisionSessions;
        this.client = client;
        this.persistence = persistence;
    }

    /**
     * 하는 일: 평일 14:25·14:35에 그날 ACTIVE 정밀 후보의 일봉을 보완해요. 실패해도 로그만 남겨요.
     * 받는 것·돌려주는 것: 없음(스케줄러가 호출해요).
     */
    @Scheduled(cron = "${closing.daily-backfill.precision-cron:0 25,35 14 * * MON-FRI}", zone = "Asia/Seoul")
    public void scheduledPreparePrecision() {
        // 마감이 늦춰진 날(수능일 등)은 아래 한 시간 뒤 실행이 맡아요.
        if (!MarketSessionHours.shift(calendar.today()).isZero()) return;
        runPreparePrecision();
    }

    /** 하는 일: 마감이 한 시간 늦춰진 날(수능일 등)에만 15:25·15:35에 같은 보완을 해요. */
    @Scheduled(cron = "0 25,35 15 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledPreparePrecisionDelayed() {
        if (MarketSessionHours.shift(calendar.today()).isZero()) return;
        runPreparePrecision();
    }

    /**
     * 하는 일: 정밀 구독 고정(기본 14:50) 직후인 14:52에 한 번 더 보완해요. 마감이 한 시간 늦춰진 날은 15:52예요.
     * 14:35 보완 뒤 14:50 고정 전에 새로 들어온 정밀 종목이 일봉 없이 15:15 평가를 받던 빈틈을 막아요
     * (10-28 064290이 14:36에 들어와 DAILY_DATA_STALE). 이미 준비된 종목은 건너뛰어 새 종목만 받아요.
     */
    @Scheduled(cron = "0 52 14,15 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledPreparePrecisionAfterFreeze() {
        if (!MarketSessionHours.firesAt(calendar.now(), java.time.LocalTime.of(14, 52))) return;
        runPreparePrecision();
    }

    private void runPreparePrecision() {
        if (!settings.enabled() || !kis.enabled()) return;
        try {
            PrepareResult result = preparePrecision();
            log.info("Precision daily candle preparation: target={}, candidates={}, saved={}, ready={}, failed={}",
                    result.recommendationDate(), result.candidateStocks(), result.savedCandles(),
                    result.readyStocks(), result.failedStocks());
        } catch (RuntimeException error) {
            log.warn("Precision daily candle preparation failed: {}", error.getMessage());
        }
    }

    /**
     * 하는 일: 평일 08:10에 후보 종목 전체의 일봉을 준비해요. 실패해도 로그만 남겨요.
     * 받는 것·돌려주는 것: 없음(스케줄러가 호출해요).
     */
    @Scheduled(cron = "${closing.daily-backfill.cron:0 10 8 * * MON-FRI}", zone = "Asia/Seoul")
    public void scheduledPrepare() {
        if (!settings.enabled() || !kis.enabled()) return;
        try {
            PrepareResult result = prepare();
            log.info("Daily candle preparation: target={}, attempted={}, saved={}, ready={}, failed={}",
                    result.recommendationDate(), result.attemptedStocks(), result.savedCandles(),
                    result.readyStocks(), result.failedStocks());
        } catch (RuntimeException error) {
            log.warn("Daily candle preparation failed: {}", error.getMessage());
        }
    }

    /**
     * 하는 일: 정밀 후보·최근 탐지·관심종목을 모은 후보 목록의 일봉을 준비해요. 동시에 두 번 돌지 않게 synchronized예요.
     * 받는 것: 없음. 돌려주는 것: 준비 결과 요약.
     */
    public synchronized PrepareResult prepare() {
        TargetWindow window = targetWindow();
        return prepare(window, candidates(window.target(), window.latestRequired()));
    }

    /**
     * 하는 일: 대상 날짜의 ACTIVE 정밀 후보만 골라 일봉을 준비해요.
     * 받는 것: 없음. 돌려주는 것: 준비 결과 요약.
     */
    public synchronized PrepareResult preparePrecision() {
        TargetWindow window = targetWindow();
        // 대상은 지금 실시간 구독 중인 모든 종목(등록부: 정밀·전날 추천 관측·관심 고정·수동)과 오늘 날짜의 활성 정밀 세션이에요.
        // 15:15 정밀 후보는 실시간 구독 종목의 탐지에서 나오므로 일봉 준비 범위도 같아야 해요.
        // - 2026-10-20·21: 오늘 세션 기록만 봐서 전날부터 이어진 정밀 종목이 빠져 DAILY_DATA_STALE(15건, 11건).
        // - 2026-10-26: 전날 추천 관측(OVERNIGHT)으로만 구독된 100250·001510이 08:10 준비(40종목 상한)와 14:25 보완(정밀만)
        //   모두에서 빠져 일봉 없이 평가돼 DAILY_DATA_MISSING.
        java.util.LinkedHashSet<String> codes = new java.util.LinkedHashSet<>();
        if (registry != null)
            codes.addAll(registry.entries().keySet());
        precisionSessions.findBySessionDateAndStatusOrderByRequestedAtAsc(window.target(),
                        PrecisionSubscriptionSession.Status.ACTIVE)
                .forEach(session -> codes.add(session.getStockCode()));
        return prepare(window, List.copyOf(codes));
    }

    /**
     * 하는 일: 종목 목록을 차례로 보며 일봉이 모자란 종목만 KIS에서 받아 저장해요.
     * 받는 것: 대상 날짜 창(추천일, 필요한 마지막 일봉 날짜), 종목코드 목록.
     * 돌려주는 것: 후보·시도·저장·준비 완료·실패·건너뜀 수가 담긴 결과.
     * 기능이나 KIS가 꺼져 있으면 IllegalStateException을 던져요.
     */
    private PrepareResult prepare(TargetWindow window, List<String> requestedCodes) {
        // 준비: 기능·인증 확인, 받을 기간 계산, 종목코드 정리(공백·중복 제거)
        if (!settings.enabled() || !kis.enabled())
            throw new IllegalStateException("Daily candle preparation or KIS integration is disabled");
        kis.requireCredentials();
        LocalDate target = window.target();
        LocalDate latestRequired = window.latestRequired();
        LocalDate from = latestRequired.minusDays(settings.lookbackDays());
        Instant now = calendar.now();
        List<String> codes = requestedCodes.stream().filter(java.util.Objects::nonNull)
                .map(String::trim).filter(code -> !code.isEmpty()).distinct().toList();
        int attempted = 0;
        int saved = 0;
        int ready = 0;
        int failed = 0;
        int skipped = 0;
        // 처리: 비활성·관리·거래정지·ETF·ETN 종목은 건너뛰고, 이미 준비됐거나 최근에 시도한 종목도 건너뛰어요.
        for (String code : codes) {
            Stock stock = stocks.findByStockCodeAndActiveTrue(code).orElse(null);
            if (stock == null || stock.isManaged() || stock.isTradingHalted() || stock.isEtf() || stock.isEtn()) {
                skipped++;
                continue;
            }
            if (persistence.ready(stock, latestRequired)) { ready++; continue; }
            Instant previous = lastAttempt.get(code);
            if (previous != null && now.isBefore(previous.plus(settings.refreshInterval()))) {
                skipped++;
                continue;
            }
            if (attempted >= settings.maxStocks()) break;
            lastAttempt.put(code, now);
            attempted++;
            // KIS에서 받아 빠진 날짜만 저장해요. 실패하면 다음 실행에서 바로 다시 시도할 수 있게 시도 기록을 지워요.
            try {
                saved += persistence.saveMissing(stock, from, latestRequired,
                        client.fetch(code, from, latestRequired));
                if (persistence.ready(stock, latestRequired)) ready++;
            } catch (RuntimeException error) {
                lastAttempt.remove(code);
                failed++;
                log.warn("Daily candles unavailable for {}: {}", code, error.getMessage());
            }
        }
        return new PrepareResult(target, latestRequired, codes.size(), attempted, saved, ready, failed, skipped);
    }

    /**
     * 하는 일: 어느 추천일을 위해 일봉을 준비할지 정해요.
     * 오늘이 거래일이고 장 마감 + 15분 전이면 오늘, 아니면 다음 거래일이 대상이에요. 필요한 마지막 일봉은 그 전 거래일이에요.
     * 돌려주는 것: (대상 추천일, 필요한 마지막 일봉 날짜).
     */
    private TargetWindow targetWindow() {
        LocalDate today = calendar.today();
        LocalDate target = calendar.isTradingDay(today)
                && calendar.now().isBefore(calendar.close(today).plus(Duration.ofMinutes(15)))
                        ? today : calendar.nextTradingDay(today);
        return new TargetWindow(target, calendar.previousTradingDay(target));
    }

    /**
     * 하는 일: 일봉을 준비할 종목 순서를 정해요(앞쪽일수록 먼저 시도해요).
     * 순서: ① 대상일 ACTIVE 정밀 후보 ② 대상일 최근 탐지 종목 ③ 직전 거래일 탐지 종목 일부(maxStocks의 절반)
     * ④ 관심종목 ⑤ 나머지 직전 거래일 탐지 종목.
     * 받는 것: 대상 추천일, 필요한 마지막 일봉 날짜. 돌려주는 것: 중복 없는 종목코드 목록.
     * 관심종목은 사용자 ID 1번 것만 읽어요(단일 사용자 전제로 보여요).
     */
    private List<String> candidates(LocalDate target, LocalDate latestRequired) {
        Set<String> activePrecision = precisionSessions
                .findBySessionDateAndStatusOrderByRequestedAtAsc(target, PrecisionSubscriptionSession.Status.ACTIVE)
                .stream().map(PrecisionSubscriptionSession::getStockCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Instant todayStart = target.atStartOfDay(ClosingTradingCalendar.ZONE).toInstant();
        Instant start = latestRequired.atStartOfDay(ClosingTradingCalendar.ZONE).toInstant();
        Set<String> todayRecent = new LinkedHashSet<>(
                detections.findRecentStockCodesForSession(target, todayStart, PageRequest.of(0, 500)));
        Set<String> recent = new LinkedHashSet<>();
        recent.addAll(detections.findRecentStockCodesForSession(latestRequired, start, PageRequest.of(0, 500)));
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        ordered.addAll(activePrecision);
        ordered.addAll(todayRecent);
        recent.stream().limit(Math.max(1, settings.maxStocks() / 2)).forEach(ordered::add);
        ordered.addAll(watchlist.findDistinctStockCodesByOwnerId(1L));
        ordered.addAll(recent);
        return new ArrayList<>(ordered);
    }

    /** 준비 대상 날짜 묶음이에요. target은 추천일, latestRequired는 그 전 거래일(필요한 마지막 일봉 날짜)이에요. */
    private record TargetWindow(LocalDate target, LocalDate latestRequired) { }

    /**
     * 일봉 준비 결과 요약이에요. candidateStocks: 후보 수, attemptedStocks: KIS를 부른 종목 수, savedCandles: 새로 저장한 일봉 수,
     * readyStocks: 준비 완료 종목 수, failedStocks: 실패 수, skippedStocks: 대상 아님·최근 시도로 건너뛴 수예요.
     */
    public record PrepareResult(LocalDate recommendationDate, LocalDate latestRequiredDate,
            int candidateStocks, int attemptedStocks, int savedCandles, int readyStocks,
            int failedStocks, int skippedStocks) { }
}
