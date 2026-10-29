/*
 * 연구용 장중 데이터(분봉)를 과거 1년치로 만들고 매일 이어 붙이는 수집기예요.
 * 날마다 "그날 거래가 된 종목"(전날 거래대금 5억 원 이상 또는 당일 10억 원 이상) 명단을 일봉 DB로 만들고,
 * 그 종목들의 그날 분봉을 KIS 과거 분봉 조회로 받아 5분봉으로 묶어 intraday_research_bar에 저장해요.
 * 오를 것 같은 종목을 미리 고르지 않고 출석만으로 명단을 정해서, 어떤 장중 흐름이 다음 날 상승으로 이어지는지 공정하게 보려는 거예요.
 * 점수나 선정 로직은 이 데이터를 읽지 않아요.
 */
package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.candle.config.IntradayResearchProperties;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.closing.application.MarketSessionHours;
import com.sunmo.stockplatform.kis.candle.KisMinuteCandleClient;
import com.sunmo.stockplatform.kis.candle.MinuteCandle;
import com.sunmo.stockplatform.kis.config.KisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** 장중 연구 데이터 수집 담당이에요. 날마다 출석부를 만들고, 출석한 학생의 하루를 1분마다 적어 5분 단위로 정리해 둬요. */
@Service
public class IntradayResearchService {
    private static final Logger log = LoggerFactory.getLogger(IntradayResearchService.class);
    // 아침 작업(07:40 공시, 08:10 일봉 준비)과 KIS 호출을 나눠 쓰지 않도록 거래일에는 07:30에 멈춰요.
    static final LocalTime MORNING_STOP = LocalTime.of(7, 30);
    // KIS 과거 분봉은 지정 시각에서 거꾸로 최대 120분을 줘요.
    static final int MINUTES_PER_CALL = 120;
    // 연속 실패가 이만큼 쌓이면 KIS 쪽 문제로 보고 이번 실행을 멈춰요.
    static final int MAX_CONSECUTIVE_FAILURES = 30;

    /** 한 종목·하루의 분봉을 가져오는 통로예요. 테스트가 응답을 직접 넣을 수 있게 분리했어요. */
    public interface MinuteSource { List<MinuteCandle> fetch(String stockCode, LocalDate date, LocalTime through); }

    /** 진행 상태예요. lastDate는 마지막으로 끝까지 받은 날짜, stoppedReason은 멈춘 이유(끝까지 받았으면 null)예요. */
    public record Status(boolean running, Instant startedAt, Instant finishedAt, LocalDate from, LocalDate to,
            LocalDate currentDate, LocalDate lastDate, int daysDone, int stockDays, int skipped, int calls, int bars,
            int failed, String stoppedReason) {
        static Status idle() { return new Status(false, null, null, null, null, null, null, 0, 0, 0, 0, 0, 0, null); }
    }

    private final IntradayResearchProperties settings;
    private final KisProperties kis;
    private final ClosingTradingCalendar calendar;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final MinuteSource minutes;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Status status = Status.idle();

    /** 스프링이 쓰는 생성자예요. KIS 과거 분봉 조회를 통로로 연결해요. */
    @Autowired
    public IntradayResearchService(IntradayResearchProperties settings, KisProperties kis, ClosingTradingCalendar calendar,
            JdbcTemplate jdbc, PlatformTransactionManager transactions, KisMinuteCandleClient client) {
        this(settings, kis, calendar, jdbc, transactions, client::fetchHistory);
    }

    /** 테스트에서도 쓰는 생성자예요. */
    IntradayResearchService(IntradayResearchProperties settings, KisProperties kis, ClosingTradingCalendar calendar,
            JdbcTemplate jdbc, PlatformTransactionManager transactions, MinuteSource minutes) {
        this.settings = settings;
        this.kis = kis;
        this.calendar = calendar;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.minutes = minutes;
    }

    /** 하는 일: 매일 19:00에 시작해요(주말 포함). 18:30 수정주가 일봉 갱신 뒤라 그날 명단까지 만들 수 있어요. */
    @Scheduled(cron = "${market.intraday-research.cron:0 0 19 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        if (settings.enabled() && kis.enabled())
            start();
    }

    /** 하는 일: 백그라운드 수집을 시작해요. 돌려주는 것: 시작했으면 true(KIS 꺼짐, 장중 보호 시간, 이미 실행 중이면 false). */
    public boolean start() {
        if (!kis.enabled() || !allowedNow() || !running.compareAndSet(false, true))
            return false;
        Thread.ofVirtual().name("intraday-research").start(() -> {
            try {
                log.info("Intraday research backfill: {}", runOnce());
            } catch (RuntimeException error) {
                log.warn("Intraday research backfill failed: {}", error.getMessage());
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    /** 하는 일: 현재(또는 마지막) 수집 상태를 돌려줘요. */
    public Status status() { return status; }

    /**
     * 하는 일: [오늘-lookback, 마지막 완결 거래일] 거래일을 오래된 날부터 차례로, 그날 명단의 아직 안 받은 종목만 받아요.
     * 돌려주는 것: 최종 상태. 장중 보호 시간이 되거나 연속 실패가 쌓이면 멈추고, 다음 실행이 이어 받아요.
     */
    Status runOnce() {
        kis.requireCredentials();
        Instant startedAt = calendar.now();
        LocalDate to = latestCompleteSession();
        LocalDate from = calendar.today().minusDays(settings.lookbackDays());
        List<LocalDate> days = jdbc.queryForList("""
                select distinct trade_date from daily_adjusted_candle where trade_date between ? and ? order by trade_date""",
                LocalDate.class, Date.valueOf(from), Date.valueOf(to));
        int daysDone = 0, stockDays = 0, skipped = 0, calls = 0, bars = 0, failed = 0, consecutive = 0;
        LocalDate last = null;
        String stopped = null;
        status = new Status(true, startedAt, null, from, to, null, null, 0, 0, 0, 0, 0, 0, null);
        outer:
        for (LocalDate day : days) {
            Set<Long> done = new HashSet<>(jdbc.queryForList(
                    "select stock_id from intraday_research_state where trade_date = ?", Long.class, Date.valueOf(day)));
            boolean complete = true;
            for (Map<String, Object> target : universe(day)) {
                long stockId = ((Number) target.get("stock_id")).longValue();
                if (done.contains(stockId)) {
                    skipped++;
                    continue;
                }
                if (!allowedNow()) {
                    stopped = "SESSION_GUARD";
                    break outer;
                }
                String code = (String) target.get("stock_code");
                try {
                    List<MinuteCandle> rows = new ArrayList<>();
                    for (LocalTime through : windows(day)) {
                        rows.addAll(minutes.fetch(code, day, through));
                        calls++;
                    }
                    bars += save(day, stockId, rows);
                    stockDays++;
                    consecutive = 0;
                } catch (RuntimeException error) {
                    failed++;
                    complete = false;
                    if (++consecutive >= MAX_CONSECUTIVE_FAILURES) {
                        stopped = "CONSECUTIVE_FAILURES: " + error.getClass().getSimpleName();
                        break outer;
                    }
                }
                status = new Status(true, startedAt, null, from, to, day, last, daysDone, stockDays, skipped, calls,
                        bars, failed, null);
            }
            if (complete)
                last = day;
            daysDone++;
        }
        status = new Status(false, startedAt, calendar.now(), from, to, null, last, daysDone, stockDays, skipped, calls,
                bars, failed, stopped);
        return status;
    }

    /**
     * 하는 일: 그날 "거래가 된 종목" 명단이에요. 전날 거래대금이 하한(기본 5억 원) 이상이거나 당일 거래대금이 하한(기본 10억 원)
     * 이상인 종목이에요. 당일 값은 장 마감 뒤 값이라, 분석할 때 15:15 누적 거래대금으로 다시 걸러 미래 참조를 없애요.
     */
    List<Map<String, Object>> universe(LocalDate day) {
        return jdbc.queryForList("""
                with x as (
                  select d.stock_id, d.trade_date, d.trading_value,
                         lag(d.trading_value) over (partition by d.stock_id order by d.trade_date) prev_value
                    from daily_adjusted_candle d where d.trade_date between ? and ?)
                select x.stock_id, s.stock_code from x join stock s on s.id = x.stock_id
                 where x.trade_date = ? and (x.trading_value >= ? or x.prev_value >= ?)
                 order by s.stock_code""",
                Date.valueOf(day.minusDays(14)), Date.valueOf(day), Date.valueOf(day),
                settings.sameDayValue(), settings.previousDayValue());
    }

    /** 하는 일: 그날 장 마감에서 개장까지 120분씩 거꾸로 나눈 조회 시각이에요(평소 15:30, 13:30, 11:30, 09:30). */
    static List<LocalTime> windows(LocalDate day) {
        LocalTime open = MarketSessionHours.open(day);
        List<LocalTime> result = new ArrayList<>();
        for (LocalTime through = MarketSessionHours.close(day); !through.isBefore(open);
                through = through.minusMinutes(MINUTES_PER_CALL)) {
            result.add(through);
            if (through.minusMinutes(MINUTES_PER_CALL).isBefore(open))
                break;
        }
        return result;
    }

    /** 하는 일: 분봉을 5분봉으로 묶어 저장하고, 받은 표시를 남겨요(봉이 없는 날도 표시해서 다시 받지 않아요). 돌려주는 것: 저장한 봉 수. */
    private int save(LocalDate day, long stockId, List<MinuteCandle> rows) {
        Map<Instant, MinuteCandle> unique = new java.util.TreeMap<>();
        rows.forEach(row -> unique.put(row.startTime(), row));
        List<CandleBackfillService.FiveMinuteBackfill> buckets = CandleBackfillService.aggregate(unique.values());
        List<Object[]> batch = new ArrayList<>();
        for (var bar : buckets)
            batch.add(new Object[] { stockId, Timestamp.from(bar.startTime()), whole(bar.open()), whole(bar.high()),
                    whole(bar.low()), whole(bar.close()), bar.volume(), bar.tradingValue().setScale(0, RoundingMode.HALF_UP).longValue() });
        transaction.executeWithoutResult(tx -> {
            if (!batch.isEmpty())
                jdbc.batchUpdate("""
                        insert into intraday_research_bar(stock_id, start_time, open_price, high_price, low_price,
                            close_price, volume, trading_value) values (?,?,?,?,?,?,?,?)""", batch);
            jdbc.update("insert into intraday_research_state(trade_date, stock_id, bars, fetched_at) values (?,?,?,?)",
                    Date.valueOf(day), stockId, batch.size(), Timestamp.from(calendar.now()));
        });
        return batch.size();
    }

    private static int whole(java.math.BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    /** 하는 일: 데이터가 완결된 마지막 거래일이에요. 오늘 장 마감 15분 뒤부터는 오늘, 그 전에는 전 거래일이에요. */
    LocalDate latestCompleteSession() {
        LocalDate today = calendar.today();
        if (calendar.isTradingDay(today) && !calendar.now().isBefore(calendar.close(today).plus(Duration.ofMinutes(15))))
            return today;
        return calendar.previousTradingDay(today);
    }

    /** 하는 일: 지금 수집해도 되는지 판단해요. 휴장일은 언제나, 거래일은 07:30 전이거나 장 마감 15분 뒤일 때만 허용해요. */
    boolean allowedNow() {
        LocalDate today = calendar.today();
        if (!calendar.isTradingDay(today))
            return true;
        Instant now = calendar.now();
        Instant stop = today.atTime(MORNING_STOP).atZone(ClosingTradingCalendar.ZONE).toInstant();
        return now.isBefore(stop) || !now.isBefore(calendar.close(today).plus(Duration.ofMinutes(15)));
    }
}
