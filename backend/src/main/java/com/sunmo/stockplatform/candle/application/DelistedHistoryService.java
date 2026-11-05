/*
 * 상장폐지 종목의 과거 일별 가격·투자자별 순매수를 받는 연구용 수집기예요.
 * 종목 마스터에는 지금 상장된 종목만 있어서 2018년 이후 연구가 생존 편향을 가져요. 그 빈 곳을 채우려고 만들었어요.
 * 목록(KRX KIND 상장폐지현황)은 API로 넣고, 종목마다 폐지일부터 floor까지 30거래일씩 거슬러 내려가요.
 * 점수나 선정 로직은 이 데이터를 읽지 않아요. 스케줄 없이 API로만 시작해요.
 */
package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.candle.config.InvestorFlowHistoryProperties;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.investor.KisInvestorDailyClient;
import com.sunmo.stockplatform.kis.investor.KisInvestorDailyClient.DailyRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 상장폐지 종목 과거 기록 수집 담당이에요. 종목마다 어디까지 받았는지 기억해서 끊겨도 이어서 받아요. */
@Service
public class DelistedHistoryService {
    private static final Logger log = LoggerFactory.getLogger(DelistedHistoryService.class);
    static final Duration RETRY_STEP = Duration.ofSeconds(30);
    static final Duration RETRY_MAX = Duration.ofMinutes(5);
    static final int MAX_CONSECUTIVE_FAILURES = 20;

    /** 기다리는 방법이에요. 테스트에서는 실제로 기다리지 않게 바꿔요. */
    interface Sleeper { void sleep(Duration duration) throws InterruptedException; }
    Sleeper sleeper = duration -> Thread.sleep(duration.toMillis());

    /** 한 종목의 기준일까지 30거래일을 가져오는 통로예요. 테스트가 응답을 직접 넣을 수 있게 분리했어요. */
    public interface RowSource { List<DailyRow> fetch(String stockCode, LocalDate date); }

    /** 목록 한 줄이에요. KRX KIND 상장폐지현황의 회사명, 종목코드, 폐지일자, 폐지사유와 시장 구분이에요. */
    public record Entry(String stockCode, String stockName, String market, LocalDate delistedDate, String reason) { }

    /** 진행 상태예요. stocks는 목록 종목 수, completed는 과거 끝까지 받은 종목 수, stoppedReason은 멈춘 이유예요. */
    public record Status(boolean running, Instant startedAt, Instant finishedAt, int stocks, int completed, int calls,
            int rows, int failed, String stoppedReason) {
        static Status idle() { return new Status(false, null, null, 0, 0, 0, 0, 0, null); }
    }

    private final InvestorFlowHistoryProperties settings;
    private final KisProperties kis;
    private final ClosingTradingCalendar calendar;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RowSource source;
    private final InvestorFlowHistoryService guard;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Status status = Status.idle();
    private int calls, rows, failed, consecutive;
    private String lastStop;

    /** 스프링이 쓰는 생성자예요. KIS 투자자 일별 조회를 통로로 연결하고, 장중 보호 시간은 수급 수집기와 같게 써요. */
    @Autowired
    public DelistedHistoryService(InvestorFlowHistoryProperties settings, KisProperties kis,
            ClosingTradingCalendar calendar, JdbcTemplate jdbc, PlatformTransactionManager transactions,
            KisInvestorDailyClient client, InvestorFlowHistoryService guard) {
        this(settings, kis, calendar, jdbc, transactions, client::fetchRows, guard);
    }

    /** 테스트에서도 쓰는 생성자예요. */
    DelistedHistoryService(InvestorFlowHistoryProperties settings, KisProperties kis, ClosingTradingCalendar calendar,
            JdbcTemplate jdbc, PlatformTransactionManager transactions, RowSource source, InvestorFlowHistoryService guard) {
        this.settings = settings;
        this.kis = kis;
        this.calendar = calendar;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.source = source;
        this.guard = guard;
    }

    /**
     * 하는 일: 목록을 넣어요. 이미 있는 종목은 이름·시장·폐지일·사유만 바꾸고 받은 범위는 그대로 둬요.
     * 돌려주는 것: 새로 넣은 종목 수.
     */
    public int register(List<Entry> entries) {
        Timestamp now = Timestamp.from(calendar.now());
        int added = 0;
        for (Entry e : entries) {
            if (e.stockCode() == null || e.stockCode().isBlank() || e.delistedDate() == null)
                throw new IllegalArgumentException("stockCode and delistedDate are required");
            int updated = jdbc.update("""
                    update delisted_stock set stock_name = ?, market = ?, delisted_date = ?, reason = ?, updated_at = ?
                     where stock_code = ?""", e.stockName(), e.market(), Date.valueOf(e.delistedDate()), e.reason(), now,
                    e.stockCode());
            if (updated == 0) {
                jdbc.update("""
                        insert into delisted_stock(stock_code, stock_name, market, delisted_date, reason, complete, updated_at)
                        values (?,?,?,?,?,?,?)""", e.stockCode(), e.stockName(), e.market(),
                        Date.valueOf(e.delistedDate()), e.reason(), false, now);
                added++;
            }
        }
        return added;
    }

    /** 하는 일: 백그라운드 수집을 시작해요. 돌려주는 것: 시작했으면 true(KIS 꺼짐, 장중 보호 시간, 이미 실행 중이면 false). */
    public boolean start() {
        if (!kis.enabled() || !guard.allowedNow() || !running.compareAndSet(false, true))
            return false;
        Thread.ofVirtual().name("delisted-history").start(() -> {
            try {
                log.info("Delisted history: {}", runOnce());
            } catch (RuntimeException error) {
                log.warn("Delisted history failed: {}", error.getMessage());
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    /** 하는 일: 현재(또는 마지막) 수집 상태를 돌려줘요. */
    public Status status() { return status; }

    /**
     * 하는 일: 완료되지 않은 종목마다 폐지일(또는 이미 받은 가장 오래된 날 전날)부터 30거래일씩 거슬러 받아요.
     * floor(수급 수집과 같은 값, 기본 2018-01-01)에 닿거나 응답이 비거나 더 내려가지 않으면 완료예요.
     * 돌려주는 것: 최종 상태. 장중 보호 시간이 되거나 연속 실패가 쌓이면 멈추고, 다음 실행이 이어 받아요.
     */
    Status runOnce() {
        kis.requireCredentials();
        Instant startedAt = calendar.now();
        calls = rows = failed = consecutive = 0;
        List<Map<String, Object>> targets = jdbc.queryForList(
                "select stock_code, delisted_date, oldest_date from delisted_stock where not complete order by stock_code");
        int stocks = count("select count(*) from delisted_stock");
        String stopped = null;
        status = new Status(true, startedAt, null, stocks, completed(), 0, 0, 0, null);
        for (Map<String, Object> t : targets) {
            String code = (String) t.get("stock_code");
            LocalDate oldest = toDate(t.get("oldest_date"));
            LocalDate cursor = oldest == null ? toDate(t.get("delisted_date")) : oldest.minusDays(1);
            while (true) {
                if (oldest != null && oldest.compareTo(settings.floor()) <= 0) {
                    markComplete(code);
                    break;
                }
                List<DailyRow> batch = fetch(code, cursor);
                if (batch == null) {
                    stopped = lastStop;
                    break;
                }
                save(code, batch);
                if (batch.isEmpty() || (oldest != null && !batch.getFirst().flow().date().isBefore(oldest))) {
                    markComplete(code);   // 더 과거가 없거나 진전이 없어요(상장 전).
                    break;
                }
                oldest = batch.getFirst().flow().date();
                cursor = oldest.minusDays(1);
            }
            if (stopped != null)
                break;
            status = new Status(true, startedAt, null, stocks, completed(), calls, rows, failed, null);
        }
        status = new Status(false, startedAt, calendar.now(), stocks, completed(), calls, rows, failed, stopped);
        return status;
    }

    /** 하는 일: 재시도를 포함해 한 번 조회해요. 돌려주는 것: 결과 목록, 멈춰야 하면 null(이유는 lastStop). */
    private List<DailyRow> fetch(String code, LocalDate cursor) {
        while (true) {
            if (!guard.allowedNow()) {
                lastStop = "SESSION_GUARD";
                return null;
            }
            try {
                List<DailyRow> batch = source.fetch(code, cursor);
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

    /** 하는 일: 한 묶음을 저장하고 받은 범위를 넓혀요. 같은 날짜가 이미 있으면 새 값으로 바꿔요. */
    private void save(String code, List<DailyRow> batch) {
        Timestamp now = Timestamp.from(calendar.now());
        transaction.executeWithoutResult(tx -> {
            if (batch.isEmpty())
                return;
            LocalDate first = batch.getFirst().flow().date(), last = batch.getLast().flow().date();
            jdbc.update("delete from delisted_daily_history where stock_code = ? and trade_date between ? and ?", code,
                    Date.valueOf(first), Date.valueOf(last));
            List<Object[]> values = new ArrayList<>();
            for (DailyRow r : batch)
                values.add(new Object[] { code, Date.valueOf(r.flow().date()), r.open(), r.high(), r.low(), r.close(),
                        r.volume(), r.tradingValue(), r.flow().foreignAmount(), r.flow().institutionAmount(),
                        r.flow().individualAmount(), r.flow().pensionAmount(), r.flow().otherCorpAmount(), now });
            jdbc.batchUpdate("""
                    insert into delisted_daily_history(stock_code, trade_date, open_price, high_price, low_price,
                        close_price, volume, trading_value, foreign_amount, institution_amount, individual_amount,
                        pension_amount, other_corp_amount, fetched_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", values);
            Map<String, Object> state = jdbc.queryForMap(
                    "select oldest_date, newest_date from delisted_stock where stock_code = ?", code);
            LocalDate o = earlier(toDate(state.get("oldest_date")), first);
            LocalDate n = later(toDate(state.get("newest_date")), last);
            jdbc.update("update delisted_stock set oldest_date = ?, newest_date = ?, updated_at = ? where stock_code = ?",
                    Date.valueOf(o), Date.valueOf(n), now, code);
        });
        rows += batch.size();
    }

    private void markComplete(String code) {
        jdbc.update("update delisted_stock set complete = ?, updated_at = ? where stock_code = ?", true,
                Timestamp.from(calendar.now()), code);
    }

    private int completed() { return count("select count(*) from delisted_stock where complete"); }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
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
}
