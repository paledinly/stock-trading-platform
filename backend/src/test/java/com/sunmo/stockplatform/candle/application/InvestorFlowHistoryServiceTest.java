/*
 * 연구용 투자자 일별 순매수 수집기가 최신 구간을 먼저 채우고, 과거로 30거래일씩 내려가 floor에서 멈추며, 다시 돌리면 이어서 받는지 확인해요.
 */
package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.candle.config.InvestorFlowHistoryProperties;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.investor.KisInvestorDailyClient.DailyFlow;
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

/** 투자자 일별 순매수 수집기를 점검하는 테스트예요. */
class InvestorFlowHistoryServiceTest {
    private DriverManagerDataSource db;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        db = new DriverManagerDataSource("jdbc:h2:mem:investor" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(db);
        jdbc.execute("create table stock(id bigint primary key, stock_code varchar(12))");
        jdbc.execute("create table daily_adjusted_candle(stock_id bigint, trade_date date)");
        jdbc.execute("""
                create table investor_daily_flow(stock_id bigint, trade_date date, foreign_qty bigint, institution_qty bigint,
                  individual_qty bigint, foreign_amount bigint, institution_amount bigint, individual_amount bigint,
                  securities_amount bigint, trust_amount bigint, private_fund_amount bigint, bank_amount bigint,
                  insurance_amount bigint, merchant_bank_amount bigint, pension_amount bigint, other_corp_amount bigint,
                  fetched_at timestamp with time zone, primary key(stock_id, trade_date))""");
        jdbc.execute("""
                create table investor_flow_state(stock_id bigint primary key, oldest_date date, newest_date date,
                  complete boolean not null default false, updated_at timestamp with time zone not null)""");
        jdbc.execute("insert into stock values (1, '000010')");
        // 종목 1은 2026-07-01부터 거래돼요(첫날 이전은 받을 필요가 없어요).
        jdbc.execute("insert into daily_adjusted_candle values (1, '2026-07-01'), (1, '2026-10-02')");
    }

    @AfterEach
    void tearDown() { jdbc.execute("shutdown"); }

    /** 토요일(휴장) 오전 10시 고정 시계로 서비스를 만들어요. 마지막 완결 거래일은 2026-10-02(금)예요. */
    private InvestorFlowHistoryService service(InvestorFlowHistoryService.FlowSource source) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse("2026-10-03T01:00:00Z"), ZoneOffset.UTC));
        KisProperties kis = mock(KisProperties.class);
        when(kis.enabled()).thenReturn(true);
        var service = new InvestorFlowHistoryService(new InvestorFlowHistoryProperties(true, LocalDate.of(2018, 1, 1)),
                kis, calendar, jdbc, new DataSourceTransactionManager(db), source);
        service.sleeper = duration -> { };
        return service;
    }

    /** 가짜 KIS예요. 기준일까지 평일 30일을 돌려주되 2026-07-01 이전은 없어요(상장 전). */
    private static List<DailyFlow> thirtyWeekdays(LocalDate through) {
        List<DailyFlow> rows = new ArrayList<>();
        LocalDate d = through;
        while (rows.size() < 30 && !d.isBefore(LocalDate.of(2026, 7, 1))) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY)
                rows.add(new DailyFlow(d, 100L, -50L, -50L, 10L, -5L, -5L, 1L, 1L, 1L, 1L, 1L, 0L, 2L, 0L));
            d = d.minusDays(1);
        }
        return rows.reversed();
    }

    /**
     * 기대: 최신 구간(10-02까지 30일)을 받은 뒤 과거로 내려가 07-01에 닿으면 완료로 표시해요.
     * 07-01 ~ 10-02의 평일이 빠짐없이 저장되고, 같은 날짜가 두 번 저장되지 않아요.
     */
    @Test
    void fillsLatestThenWalksBackToTheFirstTradingDay() {
        List<LocalDate> asked = new ArrayList<>();
        var status = service((code, date) -> { asked.add(date); return thirtyWeekdays(date); }).runOnce();

        assertThat(status.stoppedReason()).isNull();
        assertThat(asked.getFirst()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(jdbc.queryForObject("select min(trade_date) from investor_daily_flow", LocalDate.class))
                .isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(jdbc.queryForObject("select max(trade_date) from investor_daily_flow", LocalDate.class))
                .isEqualTo(LocalDate.of(2026, 10, 2));
        long weekdays = LocalDate.of(2026, 7, 1).datesUntil(LocalDate.of(2026, 10, 3))
                .filter(d -> d.getDayOfWeek().getValue() <= 5).count();
        assertThat(jdbc.queryForObject("select count(*) from investor_daily_flow", Long.class)).isEqualTo(weekdays);
        assertThat(jdbc.queryForObject("select complete from investor_flow_state where stock_id = 1", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select institution_amount from investor_daily_flow where trade_date = '2026-10-02'",
                Long.class)).isEqualTo(-5L);
    }

    /** 기대: 끝까지 받은 뒤 다시 돌리면 최신이 이미 채워져 있어 KIS를 부르지 않아요. */
    @Test
    void resumedRunDoesNotRefetchCompletedStocks() {
        service((code, date) -> thirtyWeekdays(date)).runOnce();
        List<LocalDate> asked = new ArrayList<>();
        service((code, date) -> { asked.add(date); return thirtyWeekdays(date); }).runOnce();
        assertThat(asked).isEmpty();
    }

    /** 기대: 계속 실패하면 20번째에 멈추고 이유를 남겨요. */
    @Test
    void stopsAfterPersistentFailures() {
        var status = service((code, date) -> { throw new IllegalStateException("down"); }).runOnce();
        assertThat(status.stoppedReason()).isEqualTo("CONSECUTIVE_FAILURES: IllegalStateException");
        assertThat(status.failed()).isEqualTo(20);
    }
}
