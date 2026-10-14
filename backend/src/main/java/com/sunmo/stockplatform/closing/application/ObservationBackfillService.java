package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.candle.application.CandleBackfillService;
import com.sunmo.stockplatform.candle.application.CandleGapDetector;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRunRepository;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * ADR-010: every precision candidate of the previous official run is a primary sample, but only up to ten of them
 * get a realtime slot on the next session. After the closing auction, the missing 5M bars of today's session are
 * filled from KIS minute candles (source BACKFILL) before the 15:40 observation run, without using realtime slots.
 * Realtime final bars are never overwritten. Invoked by ClosingRecommendationScheduler before observation tracking.
 */
@Component
@ConditionalOnProperty(prefix = "closing.automation", name = "enabled", havingValue = "true")
public class ObservationBackfillService {
    private static final Logger log = LoggerFactory.getLogger(ObservationBackfillService.class);
    /** The closing auction print (15:30:00..15:31:00) must be final before minute candles are read. */
    static final Duration AFTER_CLOSE = Duration.ofMinutes(2);

    public record Result(Long runId, int stocks, int filledBars, int stillMissingStocks) { }

    private final ClosingTradingCalendar calendar;
    private final ClosingRecommendationRunRepository runs;
    private final StockRepository stocks;
    private final StockCandleRepository candles;
    private final CandleBackfillService backfill;
    private final JdbcTemplate jdbc;
    private final CandleGapDetector gaps = new CandleGapDetector();

    public ObservationBackfillService(ClosingTradingCalendar calendar, ClosingRecommendationRunRepository runs,
            StockRepository stocks, StockCandleRepository candles, CandleBackfillService backfill, JdbcTemplate jdbc) {
        this.calendar = calendar;
        this.runs = runs;
        this.stocks = stocks;
        this.candles = candles;
        this.backfill = backfill;
        this.jdbc = jdbc;
    }

    /**
     * Called by the closing scheduler right before the close + 10m observation run, so bars are filled before they are
     * judged. A separate cron registration left no trace on 2026-10-14, so this rides the path that is known to run.
     * Every outcome, including a skip, is logged.
     */
    public Result fillTodayLogged() {
        try {
            Result result = fillToday();
            log.info("Observation backfill: {}", result == null ? "skipped (not after close on a trading day, or no "
                    + "previous official run)" : result);
            return result;
        } catch (RuntimeException error) {
            log.warn("Observation backfill failed: {}", error.getMessage());
            return null;
        }
    }

    Result fillToday() {
        LocalDate today = calendar.today();
        Instant now = calendar.now();
        if (!calendar.isTradingDay(today) || now.isBefore(calendar.close(today).plus(AFTER_CLOSE)))
            return null;
        LocalDate previous = calendar.previousTradingDay(today);
        ClosingRecommendationRun run = runs.findFirstByRecommendationDateAndExecutionModeOrderByIdDesc(previous, "FORWARD")
                .orElse(null);
        if (run == null)
            return null;
        List<Long> stockIds = jdbc.queryForList("""
                select stock_id from closing_candidate_observation where run_id = ? and candidate_source = 'PRECISION'
                union
                select stock_id from closing_recommendation where run_id = ?""", Long.class, run.getId(), run.getId());
        Instant open = today.atTime(9, 0).atZone(ClosingTradingCalendar.ZONE).toInstant();
        Instant close = calendar.close(today);
        int filled = 0;
        int stillMissing = 0;
        for (Long stockId : stockIds) {
            Stock stock = stocks.findById(stockId).orElse(null);
            if (stock == null)
                continue;
            List<Instant> missing = missing(stock, today, open, close);
            if (missing.isEmpty())
                continue;
            filled += backfill.backfillToday(stock, missing, now);
            if (!missing(stock, today, open, close).isEmpty())
                stillMissing++;
        }
        return new Result(run.getId(), stockIds.size(), filled, stillMissing);
    }

    private List<Instant> missing(Stock stock, LocalDate today, Instant open, Instant close) {
        List<Instant> existing = candles
                .findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        stock.getId(), "5M", open, close)
                .stream().filter(StockCandle::isFinalCandle)
                // A flat zero-volume 15:25 bar has no closing print yet; refill it.
                .filter(candle -> !isFlatClosingBar(candle, today))
                .map(StockCandle::getStartTime).toList();
        // 15:20 is the closing call (no trades) and is never required; drop it from the gaps.
        Instant closingCall = today.atTime(15, 20).atZone(ClosingTradingCalendar.ZONE).toInstant();
        return gaps.gaps(today, close, existing).stream().filter(gap -> !gap.equals(closingCall)).toList();
    }

    private static boolean isFlatClosingBar(StockCandle candle, LocalDate today) {
        Instant closingBar = today.atTime(15, 25).atZone(ClosingTradingCalendar.ZONE).toInstant();
        return candle.getStartTime().equals(closingBar) && candle.getVolume() <= 0;
    }
}
