/*
 * 연구용 장중 데이터 수집기가 날마다 출석 기준으로 명단을 만들고, 이미 받은 종목은 건너뛰며, 분봉을 5분봉으로 저장하는지 확인해요.
 */
package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.candle.config.IntradayResearchProperties;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.kis.candle.MinuteCandle;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 장중 연구 데이터 수집기를 점검하는 테스트예요. */
class IntradayResearchServiceTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private DriverManagerDataSource db;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        db = new DriverManagerDataSource("jdbc:h2:mem:intraday" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(db);
        jdbc.execute("create table stock(id bigint primary key, stock_code varchar(12))");
        jdbc.execute("create table daily_adjusted_candle(stock_id bigint, trade_date date, trading_value numeric)");
        jdbc.execute("create table intraday_research_bar(stock_id bigint, start_time timestamp with time zone, open_price int, "
                + "high_price int, low_price int, close_price int, volume bigint, trading_value bigint, primary key(stock_id, start_time))");
        jdbc.execute("create table intraday_research_state(trade_date date, stock_id bigint, bars int, "
                + "fetched_at timestamp with time zone, primary key(trade_date, stock_id))");
        jdbc.execute("insert into stock values (1, '000010'), (2, '000020'), (3, '000030'), (4, '000040')");
        // 10-01 → 10-02. 종목 1: 전날 6억(통과). 종목 2: 전날 1억, 당일 12억(통과). 종목 3: 둘 다 미달. 종목 4: 이미 받음.
        jdbc.execute("insert into daily_adjusted_candle values (1, '2026-10-01', 600000000), (1, '2026-10-02', 100000000), "
                + "(2, '2026-10-01', 100000000), (2, '2026-10-02', 1200000000), (3, '2026-10-01', 100000000), "
                + "(3, '2026-10-02', 200000000), (4, '2026-10-01', 900000000), (4, '2026-10-02', 900000000)");
        jdbc.execute("insert into intraday_research_state values ('2026-10-02', 4, 78, timestamp with time zone '2026-10-02 20:00:00+09')");
    }

    @AfterEach
    void tearDown() { jdbc.execute("shutdown"); }

    /** 토요일(휴장) 오전 10시 고정 시계로 서비스를 만들어요. 마지막 완결 거래일은 10-02(금)예요. */
    private IntradayResearchService service(IntradayResearchService.MinuteSource source) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse("2026-10-03T01:00:00Z"), ZoneOffset.UTC));
        KisProperties kis = mock(KisProperties.class);
        when(kis.enabled()).thenReturn(true);
        return new IntradayResearchService(new IntradayResearchProperties(true, 1, null, null), kis, calendar, jdbc,
                new DataSourceTransactionManager(db), source);
    }

    /**
     * 기대: 10-02 명단은 종목 1·2(종목 3은 미달, 종목 4는 이미 받음). 종목마다 4번(15:30·13:30·11:30·09:30) 조회하고,
     * 같은 5분 칸 분봉은 하나의 5분봉으로 묶어 저장해요. 받은 표시도 남아요.
     */
    @Test
    void fetchesOnlyTradableStocksOnceAndStoresFiveMinuteBars() {
        List<String> calls = new ArrayList<>();
        var service = service((code, date, through) -> {
            calls.add(code + "@" + through);
            if (!through.equals(LocalTime.of(9, 30)))
                return List.of();
            return List.of(minute(date, "09:00", 100, 10), minute(date, "09:01", 102, 5), minute(date, "09:05", 101, 7));
        });

        var status = service.runOnce();

        assertThat(calls).containsExactly("000010@15:30", "000010@13:30", "000010@11:30", "000010@09:30",
                "000020@15:30", "000020@13:30", "000020@11:30", "000020@09:30");
        assertThat(status.stockDays()).isEqualTo(2);
        assertThat(status.skipped()).isEqualTo(1);
        assertThat(status.lastDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(jdbc.queryForObject("select count(*) from intraday_research_bar where stock_id = 1", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select volume from intraday_research_bar where stock_id = 1 order by start_time limit 1",
                Long.class)).isEqualTo(15);
        assertThat(jdbc.queryForObject("select count(*) from intraday_research_state where trade_date = '2026-10-02'",
                Integer.class)).isEqualTo(3);
    }

    /** 기대: 같은 실행을 다시 하면 이미 받은 종목은 모두 건너뛰고 KIS를 부르지 않아요. */
    @Test
    void resumedRunSkipsStockDaysAlreadyFetched() {
        service((code, date, through) -> List.of()).runOnce();
        List<String> calls = new ArrayList<>();
        var status = service((code, date, through) -> { calls.add(code); return List.of(); }).runOnce();
        assertThat(calls).isEmpty();
        assertThat(status.skipped()).isEqualTo(3);
    }

    /** 기대: 평소는 15:30부터 120분씩 4번, 개장만 늦춘 날(10:00~15:30)은 3번이에요. */
    @Test
    void windowsCoverTheSessionFromCloseToOpen() {
        assertThat(IntradayResearchService.windows(LocalDate.of(2026, 10, 2)))
                .containsExactly(LocalTime.of(15, 30), LocalTime.of(13, 30), LocalTime.of(11, 30), LocalTime.of(9, 30));
    }

    private static MinuteCandle minute(LocalDate date, String time, int price, long volume) {
        BigDecimal p = BigDecimal.valueOf(price);
        return new MinuteCandle(date.atTime(LocalTime.parse(time)).atZone(SEOUL).toInstant(), p, p, p, p, volume, null);
    }
}
