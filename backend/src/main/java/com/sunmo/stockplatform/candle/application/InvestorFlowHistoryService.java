/*
 * 연구용 투자자(외국인·기관·개인) 일별 순매수를 종목마다 과거까지 받고 매일 이어 붙이는 수집기예요.
 * 기관·외국인이 며칠 연속 사는 종목의 이후 성과를 검증하려고 만들었어요. 점수나 선정 로직은 이 데이터를 읽지 않아요.
 * KIS 조회 한 번에 기준일까지 30거래일을 줘서, 최신 구간을 먼저 채운 뒤 오래된 쪽으로 30일씩 거슬러 내려가요.
 */
package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.candle.config.InvestorFlowHistoryProperties;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.investor.KisInvestorDailyClient;
import com.sunmo.stockplatform.kis.investor.KisInvestorDailyClient.DailyFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 투자자 일별 순매수 수집 담당이에요. 종목마다 "어디까지 받았는지"를 기억해서, 끊겨도 이어서 받아요. */
@Service
public class InvestorFlowHistoryService {
    private static final Logger log = LoggerFactory.getLogger(InvestorFlowHistoryService.class);
    // 장중 연구 분봉 수집과 같은 보호 시간이에요. 거래일에는 07:30에 멈추고 장 마감 15분 뒤부터 다시 받아요.
    static final LocalTime MORNING_STOP = LocalTime.of(7, 30);
    static final Duration RETRY_STEP = Duration.ofSeconds(30);
    static final Duration RETRY_MAX = Duration.ofMinutes(5);
    static final int MAX_CONSECUTIVE_FAILURES = 20;

    /** 기다리는 방법이에요. 테스트에서는 실제로 기다리지 않게 바꿔요. */
    interface Sleeper { void sleep(Duration duration) throws InterruptedException; }
    Sleeper sleeper = duration -> Thread.sleep(duration.toMillis());

    /** 한 종목의 기준일까지 30거래일을 가져오는 통로예요. 테스트가 응답을 직접 넣을 수 있게 분리했어요. */
    public interface FlowSource { List<DailyFlow> fetch(String stockCode, LocalDate date); }

    /**
     * 진행 상태예요. stocks는 대상 종목 수, updated는 최신 구간을 채운 종목 수, completed는 과거 끝까지 받은 종목 수(누적),
     * stoppedReason은 멈춘 이유(끝까지 했으면 null)예요.
     */
    public record Status(boolean running, Instant startedAt, Instant finishedAt, LocalDate through, String phase,
            int stocks, int updated, int completed, int calls, int rows, int failed, String stoppedReason) {
        static Status idle() { return new Status(false, null, null, null, null, 0, 0, 0, 0, 0, 0, null); }
    }

    private final InvestorFlowHistoryProperties settings;
    private final KisProperties kis;
    private final ClosingTradingCalendar calendar;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final FlowSource source;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Status status = Status.idle();
    private int calls, rows, failed, consecutive;

    /** 스프링이 쓰는 생성자예요. KIS 투자자 일별 조회를 통로로 연결해요. */
    @Autowired
    public InvestorFlowHistoryService(InvestorFlowHistoryProperties settings, KisProperties kis,
            ClosingTradingCalendar calendar, JdbcTemplate jdbc, PlatformTransactionManager transactions,
            KisInvestorDailyClient client) {
        this(settings, kis, calendar, jdbc, transactions, client::fetch);
    }

    /** 테스트에서도 쓰는 생성자예요. */
    InvestorFlowHistoryService(InvestorFlowHistoryProperties settings, KisProperties kis, ClosingTradingCalendar calendar,
            JdbcTemplate jdbc, PlatformTransactionManager transactions, FlowSource source) {
        this.settings = settings;
        this.kis = kis;
        this.calendar = calendar;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.source = source;
    }

    /** 하는 일: 매일 20:00에 시작해요(주말 포함). KIS 확정 투자자 집계와 18:30 수정주가 일봉 갱신 뒤예요. */
    @Scheduled(cron = "${market.investor-flow.cron:0 0 20 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        if (settings.enabled() && kis.enabled())
            start();
    }

    /** 하는 일: 백그라운드 수집을 시작해요. 돌려주는 것: 시작했으면 true(KIS 꺼짐, 장중 보호 시간, 이미 실행 중이면 false). */
    public boolean start() {
        if (!kis.enabled() || !allowedNow() || !running.compareAndSet(false, true))
            return false;
        Thread.ofVirtual().name("investor-flow").start(() -> {
            try {
                log.info("Investor flow history: {}", runOnce());
            } catch (RuntimeException error) {
                log.warn("Investor flow history failed: {}", error.getMessage());
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    /** 하는 일: 현재(또는 마지막) 수집 상태를 돌려줘요. */
    public Status status() { return status; }

    /**
     * 하는 일: 1단계로 모든 종목의 최신 구간(마지막 완결 거래일까지)을 채우고, 2단계로 과거를 30거래일씩 거슬러 받아요.
     * 과거는 floor(기본 2018-01-01) 또는 그 종목의 첫 거래일에 닿거나 응답이 비면 끝이에요.
     * 돌려주는 것: 최종 상태. 장중 보호 시간이 되거나 연속 실패가 쌓이면 멈추고, 다음 실행이 이어 받아요.
     */
    Status runOnce() {
        kis.requireCredentials();
        Instant startedAt = calendar.now();
        LocalDate through = latestCompleteSession();
        calls = rows = failed = consecutive = 0;
        List<Map<String, Object>> targets = jdbc.queryForList("""
                select d.stock_id, s.stock_code, min(d.trade_date) first_day, max(d.trade_date) last_day
                  from daily_adjusted_candle d join stock s on s.id = d.stock_id
                 group by d.stock_id, s.stock_code order by s.stock_code""");
        int updated = 0;
        String stopped = null;
        status = new Status(true, startedAt, null, through, "LATEST", targets.size(), 0, completedCount(), 0, 0, 0, null);
        // 1단계: 최신 구간. 마지막으로 받은 날 뒤로 빈 곳이 없을 때까지 기준일을 당기며 받아요.
        for (Map<String, Object> t : targets) {
            long stockId = ((Number) t.get("stock_id")).longValue();
            LocalDate lastDay = ((Date) t.get("last_day")).toLocalDate();
            if (lastDay.isBefore(through.minusDays(45)))
                continue;   // 오래전에 거래가 끝난 종목은 최신 구간이 없어요(과거 단계에서만 받아요).
            Map<String, Object> state = state(stockId);
            LocalDate newest = state == null ? null : toDate(state.get("newest_date"));
            if (newest != null && !newest.isBefore(through))
                continue;
            LocalDate cursor = through;
            while (true) {
                List<DailyFlow> batch = fetch((String) t.get("stock_code"), cursor);
                if (batch == null) {
                    stopped = stopReason();
                    break;
                }
                save(stockId, batch);
                if (batch.isEmpty() || newest == null || !batch.getFirst().date().isAfter(newest))
                    break;
                cursor = batch.getFirst().date().minusDays(1);
            }
            if (stopped != null)
                break;
            updated++;
            status = new Status(true, startedAt, null, through, "LATEST", targets.size(), updated, completedCount(),
                    calls, rows, failed, null);
        }
        // 2단계: 과거. 종목 하나를 끝까지 내려간 뒤 다음 종목으로 가요.
        if (stopped == null) {
            for (Map<String, Object> t : targets) {
                long stockId = ((Number) t.get("stock_id")).longValue();
                LocalDate floor = later(settings.floor(), ((Date) t.get("first_day")).toLocalDate());
                Map<String, Object> state = state(stockId);
                if (state != null && Boolean.TRUE.equals(state.get("complete")))
                    continue;
                LocalDate oldest = state == null ? null : toDate(state.get("oldest_date"));
                LocalDate cursor = oldest == null ? ((Date) t.get("last_day")).toLocalDate() : oldest.minusDays(1);
                while (true) {
                    if (oldest != null && oldest.compareTo(floor) <= 0) {
                        markComplete(stockId);
                        break;
                    }
                    List<DailyFlow> batch = fetch((String) t.get("stock_code"), cursor);
                    if (batch == null) {
                        stopped = stopReason();
                        break;
                    }
                    save(stockId, batch);
                    if (batch.isEmpty() || (oldest != null && !batch.getFirst().date().isBefore(oldest))) {
                        markComplete(stockId);   // 더 과거가 없거나 진전이 없어요(상장 전).
                        break;
                    }
                    oldest = batch.getFirst().date();
                    cursor = oldest.minusDays(1);
                }
                if (stopped != null)
                    break;
                status = new Status(true, startedAt, null, through, "HISTORY", targets.size(), updated, completedCount(),
                        calls, rows, failed, null);
            }
        }
        status = new Status(false, startedAt, calendar.now(), through, stopped == null ? "DONE" : "STOPPED",
                targets.size(), updated, completedCount(), calls, rows, failed, stopped);
        return status;
    }

    private String lastStop;

    /** 하는 일: 재시도를 포함해 한 번 조회해요. 돌려주는 것: 결과 목록, 멈춰야 하면 null(이유는 lastStop). */
    private List<DailyFlow> fetch(String code, LocalDate cursor) {
        while (true) {
            if (!allowedNow()) {
                lastStop = "SESSION_GUARD";
                return null;
            }
            try {
                List<DailyFlow> batch = source.fetch(code, cursor);
                calls++;
                consecutive = 0;
                return batch;
            } catch (RuntimeException error) {
                failed++;
                if (++consecutive >= MAX_CONSECUTIVE_FAILURES) {
                    lastStop = "CONSECUTIVE_FAILURES: " + error.getClass().getSimpleName();
                    return null;
                }
                Duration wait = RETRY_STEP.multipliedBy(consecutive);
                try {
                    sleeper.sleep(wait.compareTo(RETRY_MAX) > 0 ? RETRY_MAX : wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    lastStop = "INTERRUPTED";
                    return null;
                }
            }
        }
    }

    private String stopReason() { return lastStop; }

    /** 하는 일: 한 묶음을 저장하고 받은 범위를 넓혀요. 같은 날짜가 이미 있으면 새 값으로 바꿔요(정정 반영). */
    private void save(long stockId, List<DailyFlow> batch) {
        Timestamp now = Timestamp.from(calendar.now());
        transaction.executeWithoutResult(tx -> {
            if (!batch.isEmpty()) {
                jdbc.update("delete from investor_daily_flow where stock_id = ? and trade_date between ? and ?", stockId,
                        Date.valueOf(batch.getFirst().date()), Date.valueOf(batch.getLast().date()));
                List<Object[]> values = new ArrayList<>();
                for (DailyFlow f : batch)
                    values.add(new Object[] { stockId, Date.valueOf(f.date()), f.foreignQty(), f.institutionQty(),
                            f.individualQty(), f.foreignAmount(), f.institutionAmount(), f.individualAmount(),
                            f.securitiesAmount(), f.trustAmount(), f.privateFundAmount(), f.bankAmount(),
                            f.insuranceAmount(), f.merchantBankAmount(), f.pensionAmount(), f.otherCorpAmount(), now });
                jdbc.batchUpdate("""
                        insert into investor_daily_flow(stock_id, trade_date, foreign_qty, institution_qty, individual_qty,
                            foreign_amount, institution_amount, individual_amount, securities_amount, trust_amount,
                            private_fund_amount, bank_amount, insurance_amount, merchant_bank_amount, pension_amount,
                            other_corp_amount, fetched_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", values);
            }
            Map<String, Object> state = state(stockId);
            LocalDate oldest = batch.isEmpty() ? null : batch.getFirst().date();
            LocalDate newest = batch.isEmpty() ? null : batch.getLast().date();
            if (state == null) {
                jdbc.update("insert into investor_flow_state(stock_id, oldest_date, newest_date, complete, updated_at) values (?,?,?,?,?)",
                        stockId, oldest == null ? null : Date.valueOf(oldest), newest == null ? null : Date.valueOf(newest),
                        false, now);
            } else {
                LocalDate o = earlier(toDate(state.get("oldest_date")), oldest);
                LocalDate n = later(toDate(state.get("newest_date")), newest);
                jdbc.update("update investor_flow_state set oldest_date = ?, newest_date = ?, updated_at = ? where stock_id = ?",
                        o == null ? null : Date.valueOf(o), n == null ? null : Date.valueOf(n), now, stockId);
            }
        });
        rows += batch.size();
    }

    private void markComplete(long stockId) {
        if (state(stockId) == null)
            jdbc.update("insert into investor_flow_state(stock_id, complete, updated_at) values (?,?,?)", stockId, true,
                    Timestamp.from(calendar.now()));
        else
            jdbc.update("update investor_flow_state set complete = ?, updated_at = ? where stock_id = ?", true,
                    Timestamp.from(calendar.now()), stockId);
    }

    private Map<String, Object> state(long stockId) {
        List<Map<String, Object>> found = jdbc.queryForList(
                "select oldest_date, newest_date, complete from investor_flow_state where stock_id = ?", stockId);
        return found.isEmpty() ? null : found.getFirst();
    }

    private int completedCount() {
        Integer n = jdbc.queryForObject("select count(*) from investor_flow_state where complete", Integer.class);
        return n == null ? 0 : n;
    }

    private static LocalDate toDate(Object value) {
        return value == null ? null : value instanceof Date d ? d.toLocalDate() : (LocalDate) value;
    }

    private static LocalDate earlier(LocalDate a, LocalDate b) {
        return a == null ? b : b == null ? a : a.isBefore(b) ? a : b;
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
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
