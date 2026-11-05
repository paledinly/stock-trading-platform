/*
 * 상장폐지 종목 과거 기록 수집기가 폐지일부터 floor 또는 상장일까지 거슬러 받고, 다시 돌리면 이어 받으며, 목록을 다시 넣어도
 * 받은 범위를 지우지 않는지 확인해요.
 */
package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.candle.application.DelistedHistoryService.Entry;
import com.sunmo.stockplatform.candle.config.InvestorFlowHistoryProperties;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.investor.KisInvestorDailyClient.DailyFlow;
import com.sunmo.stockplatform.kis.investor.KisInvestorDailyClient.DailyRow;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 상장폐지 종목 과거 기록 수집기를 점검하는 테스트예요. */
class DelistedHistoryServiceTest {
    private static final LocalDate LISTED = LocalDate.of(2026, 3, 2);
    private static final LocalDate FLOOR = LocalDate.of(2018, 1, 1);
    private DriverManagerDataSource db;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        db = new DriverManagerDataSource("jdbc:h2:mem:delisted" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(db);
        jdbc.execute("""
                create table delisted_stock(stock_code varchar(12) primary key, stock_name varchar(200), market varchar(20),
                  delisted_date date not null, reason text, oldest_date date, newest_date date,
                  complete boolean not null default false, updated_at timestamp with time zone not null)""");
        jdbc.execute("""
                create table delisted_daily_history(stock_code varchar(12), trade_date date, open_price bigint,
                  high_price bigint, low_price bigint, close_price bigint, volume bigint, trading_value bigint,
                  foreign_amount bigint, institution_amount bigint, individual_amount bigint, pension_amount bigint,
                  other_corp_amount bigint, fetched_at timestamp with time zone, primary key(stock_code, trade_date))""");
    }

    @AfterEach
    void tearDown() { jdbc.execute("shutdown"); }

    /** 토요일(휴장) 오전 10시 고정 시계로 서비스를 만들어요. */
    private DelistedHistoryService service(DelistedHistoryService.RowSource source) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse("2026-10-03T01:00:00Z"), ZoneOffset.UTC));
        KisProperties kis = mock(KisProperties.class);
        when(kis.enabled()).thenReturn(true);
        var settings = new InvestorFlowHistoryProperties(true, FLOOR);
        var guard = new InvestorFlowHistoryService(settings, kis, calendar, jdbc, new DataSourceTransactionManager(db),
                (code, date) -> List.of());
        var service = new DelistedHistoryService(settings, kis, calendar, jdbc, new DataSourceTransactionManager(db),
                source, guard);
        service.sleeper = duration -> { };
        return service;
    }

    /** 가짜 KIS예요. 기준일까지 평일 30일을 돌려주되 상장일(2026-03-02) 이전은 없어요. */
    private static List<DailyRow> thirtyWeekdays(LocalDate through) {
        List<DailyRow> rows = new ArrayList<>();
        LocalDate d = through;
        while (rows.size() < 30 && !d.isBefore(LISTED)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY)
                rows.add(new DailyRow(new DailyFlow(d, 1L, 1L, -2L, 10L, -5L, -5L, 0L, 0L, 0L, 0L, 0L, 0L, 3L, 0L),
                        1000L, 1100L, 900L, 1050L, 5000L, 5_250_000L));
            d = d.minusDays(1);
        }
        return rows.reversed();
    }

    private static Entry entry(String code, LocalDate delisted) {
        return new Entry(code, "폐지회사", "KOSDAQ", delisted, "감사의견 거절");
    }

    /** 기대: 폐지일부터 상장일까지 빠짐없이 받고 완료로 표시해요. 가격·거래대금·순매수 금액이 그대로 저장돼요. */
    @Test
    void walksBackFromTheDelistingDayToTheFirstDay() {
        var service = service((code, date) -> thirtyWeekdays(date));
        assertThat(service.register(List.of(entry("000020", LocalDate.of(2026, 9, 15))))).isEqualTo(1);
        var status = service.runOnce();

        assertThat(status.stoppedReason()).isNull();
        assertThat(status.completed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select min(trade_date) from delisted_daily_history", LocalDate.class))
                .isEqualTo(LISTED);
        assertThat(jdbc.queryForObject("select max(trade_date) from delisted_daily_history", LocalDate.class))
                .isEqualTo(LocalDate.of(2026, 9, 15));
        long weekdays = LISTED.datesUntil(LocalDate.of(2026, 9, 16)).filter(d -> d.getDayOfWeek().getValue() <= 5).count();
        assertThat(jdbc.queryForObject("select count(*) from delisted_daily_history", Long.class)).isEqualTo(weekdays);
        assertThat(jdbc.queryForObject("""
                select close_price + trading_value + institution_amount + pension_amount
                  from delisted_daily_history where trade_date = '2026-09-15'""", Long.class))
                .isEqualTo(1050L + 5_250_000L - 5L + 3L);
        assertThat(jdbc.queryForObject("select oldest_date from delisted_stock", LocalDate.class)).isEqualTo(LISTED);
    }

    /** 기대: floor보다 오래된 상장 종목은 floor에 닿으면 멈춰요. */
    @Test
    void stopsAtTheFloor() {
        List<LocalDate> asked = new ArrayList<>();
        var service = service((code, date) -> {
            asked.add(date);
            List<DailyRow> rows = new ArrayList<>();
            for (LocalDate d = date.minusDays(41); !d.isAfter(date); d = d.plusDays(1))
                if (d.getDayOfWeek().getValue() <= 5)
                    rows.add(new DailyRow(new DailyFlow(d, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L),
                            1L, 1L, 1L, 1L, 1L, 1L));
            return rows;
        });
        service.register(List.of(entry("000030", LocalDate.of(2018, 6, 1))));
        service.runOnce();
        assertThat(jdbc.queryForObject("select complete from delisted_stock", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select oldest_date from delisted_stock", LocalDate.class)).isBeforeOrEqualTo(FLOOR);
        assertThat(asked.getLast()).isAfter(FLOOR.minusDays(60));
    }

    /** 기대: 목록을 다시 넣어도 받은 범위는 남고, 다시 돌리면 KIS를 부르지 않아요. */
    @Test
    void reRegisteringKeepsProgressAndResumes() {
        var first = service((code, date) -> thirtyWeekdays(date));
        first.register(List.of(entry("000020", LocalDate.of(2026, 9, 15))));
        first.runOnce();
        List<LocalDate> asked = new ArrayList<>();
        var second = service((code, date) -> { asked.add(date); return thirtyWeekdays(date); });
        assertThat(second.register(List.of(entry("000020", LocalDate.of(2026, 9, 15))))).isZero();
        second.runOnce();
        assertThat(asked).isEmpty();
        assertThat(jdbc.queryForObject("select complete from delisted_stock", Boolean.class)).isTrue();
    }

    /** 기대: 계속 실패하면 20번째에 멈추고 이유를 남겨요. 그 종목은 완료가 아니에요. */
    @Test
    void stopsAfterPersistentFailures() {
        var service = service((code, date) -> { throw new IllegalStateException("down"); });
        service.register(List.of(entry("000020", LocalDate.of(2026, 9, 15))));
        var status = service.runOnce();
        assertThat(status.stoppedReason()).isEqualTo("CONSECUTIVE_FAILURES: IllegalStateException");
        assertThat(status.failed()).isEqualTo(20);
        assertThat(jdbc.queryForObject("select complete from delisted_stock", Boolean.class)).isFalse();
    }
}
