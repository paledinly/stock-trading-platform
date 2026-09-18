package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.candle.config.DailyCandleBackfillProperties;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.kis.candle.KisDailyCandleClient;
import com.sunmo.stockplatform.kis.config.KisProperties;
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
    private final KisDailyCandleClient client;
    private final DailyCandlePersistence persistence;
    private final Map<String, Instant> lastAttempt = new ConcurrentHashMap<>();

    public DailyCandleBackfillService(DailyCandleBackfillProperties settings, KisProperties kis,
            ClosingTradingCalendar calendar, ScannerDetectionRepository detections,
            WatchlistItemRepository watchlist, StockRepository stocks, KisDailyCandleClient client,
            DailyCandlePersistence persistence) {
        this.settings = settings;
        this.kis = kis;
        this.calendar = calendar;
        this.detections = detections;
        this.watchlist = watchlist;
        this.stocks = stocks;
        this.client = client;
        this.persistence = persistence;
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
        if (!settings.enabled() || !kis.enabled())
            throw new IllegalStateException("Daily candle preparation or KIS integration is disabled");
        kis.requireCredentials();
        LocalDate today = calendar.today();
        LocalDate target = calendar.isTradingDay(today) && calendar.now().isBefore(calendar.close(today).plus(Duration.ofMinutes(15)))
                ? today : calendar.nextTradingDay(today);
        LocalDate latestRequired = calendar.previousTradingDay(target);
        LocalDate from = latestRequired.minusDays(settings.lookbackDays());
        Instant now = calendar.now();
        List<String> codes = candidates(latestRequired);
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
                failed++;
                log.warn("Daily candles unavailable for {}: {}", code, error.getMessage());
            }
        }
        return new PrepareResult(target, latestRequired, codes.size(), attempted, saved, ready, failed, skipped);
    }

    private List<String> candidates(LocalDate latestRequired) {
        Instant start = latestRequired.atStartOfDay(ClosingTradingCalendar.ZONE).toInstant();
        Set<String> recent = new LinkedHashSet<>();
        detections.findByDetectedAtGreaterThanEqualOrderByDetectedAtDesc(start, PageRequest.of(0, 500))
                .stream().filter(row -> row.getSessionDate().equals(latestRequired))
                .forEach(row -> recent.add(row.getStock().getStockCode()));
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        recent.stream().limit(Math.max(1, settings.maxStocks() / 2)).forEach(ordered::add);
        ordered.addAll(watchlist.findDistinctStockCodesByOwnerId(1L));
        ordered.addAll(recent);
        return new ArrayList<>(ordered);
    }

    public record PrepareResult(LocalDate recommendationDate, LocalDate latestRequiredDate,
            int candidateStocks, int attemptedStocks, int savedCandles, int readyStocks,
            int failedStocks, int skippedStocks) { }
}
