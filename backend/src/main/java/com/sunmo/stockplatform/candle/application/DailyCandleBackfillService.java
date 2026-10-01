package com.sunmo.stockplatform.candle.application;

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
    private final Map<String, Instant> lastAttempt = new ConcurrentHashMap<>();

    public DailyCandleBackfillService(DailyCandleBackfillProperties settings, KisProperties kis,
            ClosingTradingCalendar calendar, ScannerDetectionRepository detections,
            WatchlistItemRepository watchlist, StockRepository stocks, KisDailyCandleClient client,
            DailyCandlePersistence persistence, PrecisionSubscriptionSessionRepository precisionSessions) {
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

    @Scheduled(cron = "${closing.daily-backfill.precision-cron:0 25,35 14 * * MON-FRI}", zone = "Asia/Seoul")
    public void scheduledPreparePrecision() {
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

    public synchronized PrepareResult prepare() {
        TargetWindow window = targetWindow();
        return prepare(window, candidates(window.target(), window.latestRequired()));
    }

    public synchronized PrepareResult preparePrecision() {
        TargetWindow window = targetWindow();
        List<String> codes = precisionSessions
                .findBySessionDateAndStatusOrderByRequestedAtAsc(window.target(),
                        PrecisionSubscriptionSession.Status.ACTIVE)
                .stream().map(PrecisionSubscriptionSession::getStockCode).distinct().toList();
        return prepare(window, codes);
    }

    public synchronized PrepareResult prepareCodes(List<String> stockCodes) {
        return prepare(targetWindow(), stockCodes == null ? List.of() : stockCodes);
    }

    private PrepareResult prepare(TargetWindow window, List<String> requestedCodes) {
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

    private TargetWindow targetWindow() {
        LocalDate today = calendar.today();
        LocalDate target = calendar.isTradingDay(today)
                && calendar.now().isBefore(calendar.close(today).plus(Duration.ofMinutes(15)))
                        ? today : calendar.nextTradingDay(today);
        return new TargetWindow(target, calendar.previousTradingDay(target));
    }

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

    private record TargetWindow(LocalDate target, LocalDate latestRequired) { }

    public record PrepareResult(LocalDate recommendationDate, LocalDate latestRequiredDate,
            int candidateStocks, int attemptedStocks, int savedCandles, int readyStocks,
            int failedStocks, int skippedStocks) { }
}
