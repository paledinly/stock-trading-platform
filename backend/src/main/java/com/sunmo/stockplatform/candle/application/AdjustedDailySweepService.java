/*
 * 연구용으로 ETF·ETN을 뺀 전 종목의 수정주가 일봉을 KIS에서 받아 daily_adjusted_candle 테이블에 쌓는 서비스예요.
 * 처음엔 긴 기간을 받고 이후엔 최근 구간만 다시 읽으며, 겹친 구간 가격이 바뀌면 기업행사로 과거가 재계산된 것으로 보고 전체를 다시 받아요.
 * 장중(08:30~장 마감 + 15분)에는 실시간·순위 호출과 겹치지 않게 실행하지 않아요. 평일 18:30 스케줄과 AdjustedDailySweepController가 실행해요.
 */
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
 *
 * 전 종목 수정주가 일봉 수집 담당이에요. 도서관 장서 점검처럼 서가(종목)를 하나씩 돌며,
 * 이미 최신까지 정리된 칸은 건너뛰고 비었거나 낡은 칸만 새 자료로 채워요. 중간에 멈춰도 다음에 이어서 할 수 있어요.
 * 수집 결과는 연구용이며 운영 추천 점수에는 쓰지 않아요.
 */
@Service
public class AdjustedDailySweepService {
    private static final Logger log = LoggerFactory.getLogger(AdjustedDailySweepService.class);
    static final int MAX_ROWS_PER_CALL = 100;
    // 종목당 최대 호출 수의 하한이에요. 실제 한도는 받을 기간(lookbackDays)에 맞춰 maxCalls()가 정해요.
    static final int MIN_CALLS_PER_STOCK = 4;
    /**
     * Sweeps are refused from this time until close + 15m on trading days.
     * 거래일에는 이 시각(08:30)부터 장 마감 + 15분까지 수집을 거절해요.
     */
    static final LocalTime SESSION_GUARD_START = LocalTime.of(8, 30);

    /**
     * 수집 진행 상태판이에요. universe: 대상 종목 수, processed: 처리한 종목 수, upToDate: 이미 최신이라 건너뛴 수,
     * fullFetches: 전체 구간을 다시 받은 수, calls: KIS 호출 수, savedRows/revisedRows: 새로 넣은/가격이 바뀐 행 수,
     * stoppedReason: 중간에 멈춘 이유(예: SESSION_GUARD)예요.
     */
    public record SweepStatus(boolean running, Instant startedAt, Instant finishedAt, LocalDate latestSession,
            int universe, int processed, int upToDate, int fullFetches, int calls, int savedRows, int revisedRows,
            int failed, String lastStockCode, String stoppedReason) {
        /** 하는 일: 한 번도 실행하지 않은 빈 상태를 만들어요. */
        static SweepStatus idle() {
            return new SweepStatus(false, null, null, null, 0, 0, 0, 0, 0, 0, 0, 0, null, null);
        }
    }

    /** 저장 결과(새로 넣은 행 수, 가격이 바뀐 행 수)예요. */
    private record Saved(int saved, int revised) { }
    /** 종목별로 어디서부터 어디까지 받아 두었는지 기록이에요. */
    private record State(LocalDate coveredFrom, LocalDate coveredThrough) { }
    /** DB에 이미 저장된 일봉 값이에요(변경 여부 비교용). */
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

    /**
     * 하는 일: 설정, KIS 설정, 거래일 달력, 종목 레포지토리, KIS 일봉 클라이언트, SQL 실행기(JdbcTemplate), 트랜잭션 관리자를 주입받아요(의존성 주입).
     */
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

    /** 하는 일: 평일 18:30(기본)에 기능과 KIS가 켜져 있으면 수집을 시작해요. 스케줄러(정해진 시각에 자동 실행하는 장치)가 불러요. */
    @Scheduled(cron = "${market.adjusted-daily.cron:0 30 18 * * MON-FRI}", zone = "Asia/Seoul")
    public void scheduled() {
        if (settings.enabled() && kis.enabled())
            start();
    }

    /**
     * Starts a background sweep; false when one is already running or the session guard is active.
     * 하는 일: 가상 스레드에서 수집을 시작하고 바로 돌아와요(비동기: 끝날 때까지 기다리지 않는 방식).
     * 돌려주는 것: 시작했으면 true, KIS 꺼짐·장중 보호 시간·이미 실행 중이면 false.
     * 수동 실행은 market.adjusted-daily.enabled 설정과 관계없이 동작해요.
     */
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

    /** 하는 일: 현재(또는 마지막) 수집 상태를 돌려줘요. */
    public SweepStatus status() {
        return status;
    }

    /**
     * Synchronous sweep; returns the final status.
     * 하는 일: 대상 종목을 하나씩 돌며 수집을 끝까지 실행해요(이 메서드는 끝날 때까지 기다려요).
     * 돌려주는 것: 최종 상태. 도중에 장중 보호 시간이 되면 SESSION_GUARD로 멈춰요.
     */
    SweepStatus runOnce() {
        // 준비: 마지막 완료 거래일과 받을 시작일, 대상 종목(활성·ETF/ETN 제외)을 정해요.
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
                // 이미 [start, latest]를 모두 덮고 있으면 건너뛰어요.
                State state = state(stock.getId());
                if (state != null && !state.coveredThrough().isBefore(latest) && !state.coveredFrom().isAfter(start)) {
                    upToDate++;
                } else {
                    // 기록이 없거나 시작일이 모자라면 전체 구간, 아니면 최근 refreshDays 구간만 받아요.
                    boolean full = state == null || state.coveredFrom().isAfter(start);
                    LocalDate from = full ? start : latest.minusDays(settings.refreshDays());
                    List<DailyCandle> rows = new ArrayList<>();
                    calls += fetch(stock.getStockCode(), from, latest, rows);
                    Saved result = save(stock.getId(), rows);
                    // A changed overlap means KIS re-based the adjusted history (corporate action): reload it all.
                    // 겹친 구간 가격이 바뀌었다면 기업행사로 수정주가가 다시 계산된 것이라 전체 구간을 다시 받아요.
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
        // 결과: 종료 시각과 멈춘 이유를 담아 최종 상태로 남겨요.
        status = new SweepStatus(false, startedAt, calendar.now(), latest, universe.size(), processed, upToDate,
                fullFetches, calls, saved, revised, failed, last, stopped);
        log.info("Adjusted daily sweep: {}", status);
        return status;
    }

    /**
     * Pages backwards from through; KIS returns at most 100 rows per call, newest first.
     * 하는 일: through부터 과거 방향으로 KIS 수정주가 일봉을 페이지 단위(최대 100행)로 받아 into에 모아요. 종목당 최대 4번 호출해요.
     * 받는 것: 종목코드, 시작일, 마지막일, 결과를 담을 목록. 돌려주는 것: KIS 호출 횟수.
     */
    private int fetch(String code, LocalDate from, LocalDate through, List<DailyCandle> into) {
        int calls = 0;
        LocalDate upper = through;
        int maxCalls = maxCalls();
        while (calls < maxCalls && !upper.isBefore(from)) {
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

    /**
     * 하는 일: 받은 일봉을 한 트랜잭션 안에서 저장해요. 없는 날짜는 새로 넣고, 가격이나 거래량이 바뀐 날짜는 고쳐 써요.
     * 받는 것: 종목 ID, 받은 일봉 목록(같은 날짜가 중복되면 마지막 것만 써요).
     * 돌려주는 것: 새로 넣은 행 수와 가격이 바뀐 행 수.
     */
    /**
     * 하는 일: 종목당 최대 호출 수를 정해요. 한 번에 100거래일(달력으로 약 145일)을 받으므로 기간을 덮을 만큼 + 1회예요.
     * 예: 210일 → 4회(하한), 1100일(약 3년) → 9회.
     */
    int maxCalls() {
        return Math.max(MIN_CALLS_PER_STOCK, (settings.lookbackDays() + 144) / 145 + 1);
    }

    private Saved save(long stockId, List<DailyCandle> rows) {
        if (rows.isEmpty())
            return new Saved(0, 0);
        return transaction.execute(tx -> {
            // 준비: 받은 기간 안에서 이미 저장된 값을 날짜별로 읽어 와요.
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
            // 처리: 날짜마다 새 행이면 insert 목록에, 값이 바뀌었으면 바로 update해요.
            for (DailyCandle row : unique.values()) {
                Stored stored = existing.get(row.date());
                if (stored == null) {
                    inserts.add(new Object[] { stockId, Date.valueOf(row.date()), row.open(), row.high(), row.low(),
                            row.close(), row.volume(), row.tradingValue(), now });
                } else if (priceChanged(stored, row) || stored.volume() != row.volume()) {
                    // Only a price change means the adjusted history was re-based; volume can move with after-hours.
                    // 가격이 바뀐 경우만 재계산(revised)으로 세요. 거래량은 시간외 거래로도 바뀔 수 있어요.
                    if (priceChanged(stored, row)) revised++;
                    jdbc.update("""
                            update daily_adjusted_candle set open_price=?, high_price=?, low_price=?, close_price=?,
                                volume=?, trading_value=?, fetched_at=? where stock_id=? and trade_date=?""",
                            row.open(), row.high(), row.low(), row.close(), row.volume(), row.tradingValue(), now,
                            stockId, Date.valueOf(row.date()));
                }
            }
            // 결과: 새 행은 한 번에 묶어 insert해요.
            if (!inserts.isEmpty())
                jdbc.batchUpdate("""
                        insert into daily_adjusted_candle(stock_id, trade_date, open_price, high_price, low_price,
                            close_price, volume, trading_value, fetched_at) values (?,?,?,?,?,?,?,?,?)""", inserts);
            return new Saved(inserts.size(), revised);
        });
    }

    /** 하는 일: 저장된 값과 새 값의 시·고·저·종가 중 하나라도 다르면 true를 돌려줘요. */
    private static boolean priceChanged(Stored stored, DailyCandle row) {
        return stored.open().compareTo(row.open()) != 0 || stored.high().compareTo(row.high()) != 0
                || stored.low().compareTo(row.low()) != 0 || stored.close().compareTo(row.close()) != 0;
    }

    /** 하는 일: 종목의 수집 범위 기록을 읽어요. 받는 것: 종목 ID. 돌려주는 것: 기록, 없으면 null. */
    private State state(long stockId) {
        return jdbc.query("select covered_from, covered_through from daily_adjusted_sweep_state where stock_id = ?",
                (rs, index) -> new State(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)), stockId)
                .stream().findFirst().orElse(null);
    }

    /** 하는 일: 종목의 수집 범위 기록을 갱신하고, 기록이 없으면 새로 넣어요. 받는 것: 종목 ID, 시작일, 마지막일. */
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

    /**
     * Today once its close + 15m has passed on a trading day; otherwise the previous trading day.
     * 하는 일: 일봉이 확정된 마지막 거래일을 구해요. 오늘이 거래일이고 장 마감 + 15분이 지났으면 오늘, 아니면 직전 거래일이에요.
     */
    LocalDate latestCompleteSession() {
        LocalDate today = calendar.today();
        if (calendar.isTradingDay(today) && !calendar.now().isBefore(calendar.close(today).plus(Duration.ofMinutes(15))))
            return today;
        return calendar.previousTradingDay(today);
    }

    /**
     * 하는 일: 지금 수집해도 되는지 판단해요. 휴장일은 언제나 허용하고,
     * 거래일에는 08:30 이전이거나 장 마감 + 15분 이후일 때만 허용해요.
     */
    boolean allowedNow() {
        LocalDate today = calendar.today();
        if (!calendar.isTradingDay(today))
            return true;
        Instant now = calendar.now();
        Instant guardStart = today.atTime(SESSION_GUARD_START).atZone(ClosingTradingCalendar.ZONE).toInstant();
        return now.isBefore(guardStart) || !now.isBefore(calendar.close(today).plus(Duration.ofMinutes(15)));
    }
}
