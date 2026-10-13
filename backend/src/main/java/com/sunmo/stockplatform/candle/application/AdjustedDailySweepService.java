package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.candle.config.AdjustedDailySweepProperties;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.kis.candle.DailyCandle;
import com.sunmo.stockplatform.kis.candle.KisDailyCandleClient;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collects adjusted (수정주가) daily bars for every listed non-ETF/ETN stock into daily_adjusted_candle, so a
 * point-in-time universe (e.g. top trading value as of each date) can be rebuilt for research. Runs only outside
 * the trading session so it never competes with realtime and ranking calls. Resumable: a stock already covered
 * through the latest complete session is skipped.
 */
@Service
public class AdjustedDailySweepService {
    private static final Logger log = LoggerFactory.getLogger(AdjustedDailySweepService.class);
    static final int MAX_ROWS_PER_CALL = 100;
    static final int MAX_CALLS_PER_STOCK = 4;
    /** Sweeps are refused from this time until close + 15m on trading days. */
    static final LocalTime SESSION_GUARD_START = LocalTime.of(8, 30);

    public record SweepStatus(boolean running, Instant startedAt, Instant finishedAt, LocalDate latestSession,
            int universe, int processed, int upToDate, int fullFetches, int calls, int savedRows, int revisedRows,
            int failed, String lastStockCode, String stoppedReason) {
        static SweepStatus idle() {
            return new SweepStatus(false, null, null, null, 0, 0, 0, 0, 0, 0, 0, 0, null, null);
        }
    }

    private record Saved(int saved, int revised) { }
    private record State(LocalDate coveredFrom, LocalDate coveredThrough) { }
    private record Stored(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) { }

    private final AdjustedDailySweepProperties settings;
    private final KisProperties kis;
    private final ClosingTradingCalendar calendar;
    private final StockRepository stocks;
    private final KisDailyCandleClient client;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile SweepStatus status = SweepStatus.idle();

    public AdjustedDailySweepService(AdjustedDailySweepProperties settings, KisProperties kis,
            ClosingTradingCalendar calendar, StockRepository stocks, KisDailyCandleClient client, JdbcTemplate jdbc,
            PlatformTransactionManager transactions) {
        this.settings = settings;
        this.kis = kis;
        this.calendar = calendar;
        this.stocks = stocks;
        this.client = client;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Scheduled(cron = "${market.adjusted-daily.cron:0 30 18 * * MON-FRI}", zone = "Asia/Seoul")
    public void scheduled() {
        if (settings.enabled() && kis.enabled())
            start();
    }

    /** Starts a background sweep; false when one is already running or the session guard is active. */
    public boolean start() {
        if (!kis.enabled() || !allowedNow() || !running.compareAndSet(false, true))
            return false;
        Thread.ofVirtual().name("adjusted-daily-sweep").start(() -> {
            try {
                runOnce();
            } catch (RuntimeException error) {
                log.warn("Adjusted daily sweep failed: {}", error.getMessage());
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    public SweepStatus status() {
        return status;
    }

    /** Synchronous sweep; returns the final status. */
    SweepStatus runOnce() {
        kis.requireCredentials();
        Instant startedAt = calendar.now();
        LocalDate latest = latestCompleteSession();
        LocalDate start = latest.minusDays(settings.lookbackDays());
        List<Stock> universe = stocks.findByActiveTrueAndEtfFalseAndEtnFalseOrderByStockCodeAsc();
        int processed = 0, upToDate = 0, fullFetches = 0, calls = 0, saved = 0, revised = 0, failed = 0;
        String last = null;
        String stopped = null;
        status = new SweepStatus(true, startedAt, null, latest, universe.size(), 0, 0, 0, 0, 0, 0, 0, null, null);
        for (Stock stock : universe) {
            if (!allowedNow()) {
                stopped = "SESSION_GUARD";
                break;
            }
            last = stock.getStockCode();
            try {
                State state = state(stock.getId());
                if (state != null && !state.coveredThrough().isBefore(latest) && !state.coveredFrom().isAfter(start)) {
                    upToDate++;
                } else {
                    boolean full = state == null || state.coveredFrom().isAfter(start);
                    LocalDate from = full ? start : latest.minusDays(settings.refreshDays());
                    List<DailyCandle> rows = new ArrayList<>();
                    calls += fetch(stock.getStockCode(), from, latest, rows);
                    Saved result = save(stock.getId(), rows);
                    // A changed overlap means KIS re-based the adjusted history (corporate action): reload it all.
                    if (!full && result.revised() > 0) {
                        full = true;
                        rows.clear();
                        calls += fetch(stock.getStockCode(), start, latest, rows);
                        Saved reloaded = save(stock.getId(), rows);
                        result = new Saved(result.saved() + reloaded.saved(), result.revised() + reloaded.revised());
                    }
                    if (full) fullFetches++;
                    saved += result.saved();
                    revised += result.revised();
                    LocalDate coveredFrom = full ? start : state.coveredFrom();
                    writeState(stock.getId(), coveredFrom, latest);
                }
            } catch (RuntimeException error) {
                failed++;
                log.warn("Adjusted daily candles unavailable for {}: {}", stock.getStockCode(), error.getMessage());
            }
            processed++;
            status = new SweepStatus(true, startedAt, null, latest, universe.size(), processed, upToDate, fullFetches,
                    calls, saved, revised, failed, last, null);
        }
        status = new SweepStatus(false, startedAt, calendar.now(), latest, universe.size(), processed, upToDate,
                fullFetches, calls, saved, revised, failed, last, stopped);
        log.info("Adjusted daily sweep: {}", status);
        return status;
    }

    /** Pages backwards from through; KIS returns at most 100 rows per call, newest first. */
    private int fetch(String code, LocalDate from, LocalDate through, List<DailyCandle> into) {
        int calls = 0;
        LocalDate upper = through;
        while (calls < MAX_CALLS_PER_STOCK && !upper.isBefore(from)) {
            List<DailyCandle> page = client.fetchAdjusted(code, from, upper);
            calls++;
            into.addAll(page);
            if (page.size() < MAX_ROWS_PER_CALL)
                break;
            LocalDate oldest = page.stream().map(DailyCandle::date).min(Comparator.naturalOrder()).orElseThrow();
            upper = oldest.minusDays(1);
        }
        return calls;
    }

    private Saved save(long stockId, List<DailyCandle> rows) {
        if (rows.isEmpty())
            return new Saved(0, 0);
        return transaction.execute(tx -> {
            LocalDate min = rows.stream().map(DailyCandle::date).min(Comparator.naturalOrder()).orElseThrow();
            LocalDate max = rows.stream().map(DailyCandle::date).max(Comparator.naturalOrder()).orElseThrow();
            Map<LocalDate, Stored> existing = new HashMap<>();
            jdbc.query("""
                    select trade_date, open_price, high_price, low_price, close_price, volume
                      from daily_adjusted_candle where stock_id = ? and trade_date between ? and ?""",
                    rs -> { existing.put(rs.getObject(1, LocalDate.class), new Stored(rs.getBigDecimal(2),
                            rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getLong(6))); },
                    stockId, Date.valueOf(min), Date.valueOf(max));
            Timestamp now = Timestamp.from(calendar.now());
            List<Object[]> inserts = new ArrayList<>();
            int revised = 0;
            Map<LocalDate, DailyCandle> unique = new HashMap<>();
            rows.forEach(row -> unique.put(row.date(), row));
            for (DailyCandle row : unique.values()) {
                Stored stored = existing.get(row.date());
                if (stored == null) {
                    inserts.add(new Object[] { stockId, Date.valueOf(row.date()), row.open(), row.high(), row.low(),
                            row.close(), row.volume(), row.tradingValue(), now });
                } else if (priceChanged(stored, row) || stored.volume() != row.volume()) {
                    // Only a price change means the adjusted history was re-based; volume can move with after-hours.
                    if (priceChanged(stored, row)) revised++;
                    jdbc.update("""
                            update daily_adjusted_candle set open_price=?, high_price=?, low_price=?, close_price=?,
                                volume=?, trading_value=?, fetched_at=? where stock_id=? and trade_date=?""",
                            row.open(), row.high(), row.low(), row.close(), row.volume(), row.tradingValue(), now,
                            stockId, Date.valueOf(row.date()));
                }
            }
            if (!inserts.isEmpty())
                jdbc.batchUpdate("""
                        insert into daily_adjusted_candle(stock_id, trade_date, open_price, high_price, low_price,
                            close_price, volume, trading_value, fetched_at) values (?,?,?,?,?,?,?,?,?)""", inserts);
            return new Saved(inserts.size(), revised);
        });
    }

    private static boolean priceChanged(Stored stored, DailyCandle row) {
        return stored.open().compareTo(row.open()) != 0 || stored.high().compareTo(row.high()) != 0
                || stored.low().compareTo(row.low()) != 0 || stored.close().compareTo(row.close()) != 0;
    }

    private State state(long stockId) {
        return jdbc.query("select covered_from, covered_through from daily_adjusted_sweep_state where stock_id = ?",
                (rs, index) -> new State(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)), stockId)
                .stream().findFirst().orElse(null);
    }

    private void writeState(long stockId, LocalDate coveredFrom, LocalDate coveredThrough) {
        Timestamp now = Timestamp.from(calendar.now());
        int updated = jdbc.update("""
                update daily_adjusted_sweep_state set covered_from=?, covered_through=?, swept_at=? where stock_id=?""",
                Date.valueOf(coveredFrom), Date.valueOf(coveredThrough), now, stockId);
        if (updated == 0)
            jdbc.update("""
                    insert into daily_adjusted_sweep_state(stock_id, covered_from, covered_through, swept_at)
                    values (?,?,?,?)""", stockId, Date.valueOf(coveredFrom), Date.valueOf(coveredThrough), now);
    }

    /** Today once its close + 15m has passed on a trading day; otherwise the previous trading day. */
    LocalDate latestCompleteSession() {
        LocalDate today = calendar.today();
        if (calendar.isTradingDay(today) && !calendar.now().isBefore(calendar.close(today).plus(Duration.ofMinutes(15))))
            return today;
        return calendar.previousTradingDay(today);
    }

    boolean allowedNow() {
        LocalDate today = calendar.today();
        if (!calendar.isTradingDay(today))
            return true;
        Instant now = calendar.now();
        Instant guardStart = today.atTime(SESSION_GUARD_START).atZone(ClosingTradingCalendar.ZONE).toInstant();
        return now.isBefore(guardStart) || !now.isBefore(calendar.close(today).plus(Duration.ofMinutes(15)));
    }
}
