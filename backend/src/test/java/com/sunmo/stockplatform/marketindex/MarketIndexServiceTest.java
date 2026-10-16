/*
 * MarketIndexService(지수 일봉 수집·최신 지수) 테스트예요. H2 메모리 DB와 가짜 KIS 지수 클라이언트로
 * 거꾸로 나눠 받기, 마감 전 오늘 일봉 건너뛰기, 갱신 저장, 장중 관측 우선·일봉 종가 대체를 확인해요.
 */
package com.sunmo.stockplatform.marketindex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.index.KisIndexDailyClient;
import com.sunmo.stockplatform.kis.index.KisIndexDailyClient.IndexDailyBar;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import com.sunmo.stockplatform.marketindex.application.MarketIndexService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketIndexServiceTest {
    // 2026-10-16(금). 시각은 UTC로 적고, KST = UTC + 9시간이에요.
    static final LocalDate FRIDAY = LocalDate.of(2026, 10, 16);
    EmbeddedDatabase db;
    JdbcTemplate jdbc;
    KisIndexDailyClient client;

    @BeforeEach void setup() {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(db);
        jdbc.execute("create table market_index_daily(index_code varchar(10) not null, index_name varchar(20) not null, "
                + "trade_date date not null, open numeric(14,4), high numeric(14,4), low numeric(14,4), close numeric(14,4) not null, "
                + "volume bigint, trading_value numeric(24,4), fetched_at timestamp with time zone not null, "
                + "primary key(index_code, trade_date))");
        jdbc.execute("create table closing_context_observation(kind varchar(30), symbol varchar(20), scope varchar(30), "
                + "received_at timestamp with time zone, payload text)");
        client = mock(KisIndexDailyClient.class);
    }

    @AfterEach void close() { db.shutdown(); }

    MarketIndexService service(String nowUtc) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse(nowUtc), ZoneOffset.UTC));
        var kis = new KisProperties(true, null, "k", "s", null, null, null, null);
        return new MarketIndexService(client, kis, calendar, jdbc, new ObjectMapper());
    }

    /** from~through 사이 평일마다 종가 = 100 + 날짜 순번인 일봉을 만들어요(최대 limit개, 최근 쪽부터). */
    static List<IndexDailyBar> bars(LocalDate from, LocalDate through, int limit) {
        List<IndexDailyBar> rows = new ArrayList<>();
        for (LocalDate d = through; !d.isBefore(from) && rows.size() < limit; d = d.minusDays(1)) {
            if (d.getDayOfWeek().getValue() >= 6) continue;
            BigDecimal close = BigDecimal.valueOf(100 + d.getDayOfMonth());
            rows.add(0, new IndexDailyBar(d, close, close, close, close, 1000L, BigDecimal.TEN));
        }
        return rows;
    }

    @Test
    void pagesBackwardAndSkipsTodayBeforeTheCloseSettles() {
        when(client.fetch(any(), any(), any())).thenAnswer(call -> bars(call.getArgument(1), call.getArgument(2), 3));
        // 15:00 KST: 오늘(10-16) 일봉은 아직 확정 전이라 저장하지 않아요.
        var results = service("2026-10-16T06:00:00Z").backfill(LocalDate.of(2026, 10, 8), FRIDAY);

        assertThat(results).extracting(MarketIndexService.BackfillResult::name).containsExactly("KOSPI", "KOSDAQ");
        // 10-08~10-16 평일 7일을 3행씩 거꾸로: 14~16, 9~13(9,12,13), 8 → 요청 3번, 오늘 1행을 빼고 6행 저장.
        assertThat(results.get(0).requests()).isEqualTo(3);
        assertThat(results.get(0).saved()).isEqualTo(6);
        assertThat(results.get(0).oldest()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(results.get(0).newest()).isEqualTo(LocalDate.of(2026, 10, 15));
        verify(client).fetch(eq("0001"), eq(LocalDate.of(2026, 10, 8)), eq(LocalDate.of(2026, 10, 13)));
        assertThat(jdbc.queryForObject("select count(*) from market_index_daily where trade_date = ?", Integer.class, FRIDAY))
                .isZero();
    }

    @Test
    void savesTodayAfterTheCloseAndUpdatesExistingRows() {
        when(client.fetch(any(), any(), any())).thenAnswer(call -> bars(call.getArgument(1), call.getArgument(2), 50));
        var service = service("2026-10-16T07:05:00Z"); // 16:05 KST
        service.backfill(LocalDate.of(2026, 10, 15), FRIDAY);
        doReturn(List.of(new IndexDailyBar(FRIDAY, null, null, null, new BigDecimal("5700.5"), 1L, null)))
                .when(client).fetch(eq("0001"), any(), any());
        service.backfill(FRIDAY, FRIDAY);

        assertThat(jdbc.queryForObject("select count(*) from market_index_daily", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select close from market_index_daily where index_code = '0001' and trade_date = ?",
                BigDecimal.class, FRIDAY)).isEqualByComparingTo("5700.5");
        verify(client, times(2)).fetch(eq("1001"), any(), any());
    }

    @Test
    void latestPrefersTodayIntradayObservationAndFallsBackToDailyClose() {
        jdbc.update("insert into market_index_daily values ('0001', 'KOSPI', ?, null, null, null, 5500, null, null, ?), "
                + "('0001', 'KOSPI', ?, null, null, null, 5555, null, null, ?), "
                + "('1001', 'KOSDAQ', ?, null, null, null, 800, null, null, ?), "
                + "('1001', 'KOSDAQ', ?, null, null, null, 820, null, null, ?)",
                LocalDate.of(2026, 10, 14), ts("2026-10-14T07:05:00Z"), LocalDate.of(2026, 10, 15), ts("2026-10-15T07:05:00Z"),
                LocalDate.of(2026, 10, 14), ts("2026-10-14T07:05:00Z"), LocalDate.of(2026, 10, 15), ts("2026-10-15T07:05:00Z"));
        // KOSPI는 오늘 장중 관측 2건(최신 것을 써요), KOSDAQ은 어제 관측만 있어 마지막 일봉 종가로 대체해요.
        jdbc.update("insert into closing_context_observation values ('INDEX', 'KOSPI', 'KOSPI', ?, ?), "
                + "('INDEX', 'KOSPI', 'KOSPI', ?, ?), ('INDEX', 'KOSDAQ', 'KOSDAQ', ?, ?)",
                ts("2026-10-16T01:00:00Z"), "{\"value\":5600.1,\"returnPct\":0.8}",
                ts("2026-10-16T01:05:00Z"), "{\"value\":5698.79,\"returnPct\":2.6}",
                ts("2026-10-15T06:00:00Z"), "{\"value\":815.0,\"returnPct\":1.0}");

        var views = service("2026-10-16T01:10:00Z").latest();

        assertThat(views).hasSize(2);
        var kospi = views.get(0);
        assertThat(kospi.source()).isEqualTo("INTRADAY");
        assertThat(kospi.value()).isEqualByComparingTo("5698.79");
        assertThat(kospi.changePct()).isEqualByComparingTo("2.6");
        assertThat(kospi.observedAt()).isEqualTo(Instant.parse("2026-10-16T01:05:00Z"));
        assertThat(kospi.previousDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(kospi.previousClose()).isEqualByComparingTo("5555");
        var kosdaq = views.get(1);
        assertThat(kosdaq.source()).isEqualTo("DAILY_CLOSE");
        assertThat(kosdaq.value()).isEqualByComparingTo("820");
        assertThat(kosdaq.changePct()).isEqualByComparingTo("2.50");
        assertThat(kosdaq.previousDate()).isEqualTo(LocalDate.of(2026, 10, 14));
    }

    @Test
    void latestReportsNoneWhenNothingIsStored() {
        var views = service("2026-10-16T01:10:00Z").latest();
        assertThat(views).extracting(MarketIndexService.IndexView::source).containsExactly("NONE", "NONE");
    }

    static Timestamp ts(String utc) { return Timestamp.from(Instant.parse(utc)); }
}
