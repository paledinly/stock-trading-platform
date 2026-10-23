/*
 * 해외 일봉 수집(OverseasDailyService·KisOverseasDailyClient.parse) 테스트예요. KIS 응답 모양을 읽고,
 * 오늘(한국 날짜) 이후 일봉은 요청하지 않으며, 거꾸로 나눠 받고, 한 종목 실패가 다른 종목을 막지 않는지 확인해요.
 */
package com.sunmo.stockplatform.marketindex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.overseas.KisOverseasDailyClient;
import com.sunmo.stockplatform.kis.overseas.KisOverseasDailyClient.OverseasBar;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import com.sunmo.stockplatform.marketindex.application.OverseasDailyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OverseasDailyServiceTest {
    EmbeddedDatabase db;
    JdbcTemplate jdbc;
    KisOverseasDailyClient client;

    @BeforeEach void setup() {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(db);
        jdbc.execute("create table overseas_daily(symbol varchar(20) not null, source_code varchar(20) not null, "
                + "trade_date date not null, open numeric(18,6), high numeric(18,6), low numeric(18,6), close numeric(18,6) not null, "
                + "volume bigint, fetched_at timestamp with time zone not null, primary key(symbol, trade_date))");
        client = mock(KisOverseasDailyClient.class);
    }

    @AfterEach void close() { db.shutdown(); }

    OverseasDailyService service(String nowUtc) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse(nowUtc), ZoneOffset.UTC));
        return new OverseasDailyService(client, new KisProperties(true, null, "k", "s", null, null, null, null), calendar, jdbc);
    }

    static List<OverseasBar> weekdays(LocalDate from, LocalDate through, int limit) {
        List<OverseasBar> rows = new ArrayList<>();
        for (LocalDate d = through; !d.isBefore(from) && rows.size() < limit; d = d.minusDays(1)) {
            if (d.getDayOfWeek().getValue() >= 6) continue;
            rows.add(0, new OverseasBar(d, null, null, null, BigDecimal.valueOf(d.getDayOfMonth()), null));
        }
        return rows;
    }

    @Test
    void parsesKisIndexAndEtfRows() throws Exception {
        var mapper = new ObjectMapper();
        Method parse = KisOverseasDailyClient.class.getDeclaredMethod("parse", com.fasterxml.jackson.databind.JsonNode.class,
                LocalDate.class, LocalDate.class, String.class, String.class, String.class, String.class, String.class, String.class);
        parse.setAccessible(true);
        var index = mapper.readTree("{\"output2\":[{\"stck_bsop_date\":\"20261022\",\"ovrs_nmix_oprc\":\"6000.1\","
                + "\"ovrs_nmix_hgpr\":\"6010\",\"ovrs_nmix_lwpr\":\"5990\",\"ovrs_nmix_prpr\":\"6005.5\",\"acml_vol\":\"0\"},"
                + "{\"stck_bsop_date\":\"20261021\",\"ovrs_nmix_prpr\":\"0\"},{\"stck_bsop_date\":\"\",\"ovrs_nmix_prpr\":\"1\"}]}");
        @SuppressWarnings("unchecked")
        List<OverseasBar> bars = (List<OverseasBar>) parse.invoke(null, index, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 22),
                "stck_bsop_date", "ovrs_nmix_oprc", "ovrs_nmix_hgpr", "ovrs_nmix_lwpr", "ovrs_nmix_prpr", "acml_vol");
        assertThat(bars).singleElement().satisfies(bar -> {
            assertThat(bar.tradeDate()).isEqualTo(LocalDate.of(2026, 10, 22));
            assertThat(bar.close()).isEqualByComparingTo("6005.5");
            assertThat(bar.open()).isEqualByComparingTo("6000.1");
        });
        var etf = mapper.readTree("{\"output2\":[{\"xymd\":\"20261022\",\"clos\":\"95.12\",\"open\":\"94\",\"high\":\"96\","
                + "\"low\":\"93.5\",\"tvol\":\"1234567\"},{\"xymd\":\"20260930\",\"clos\":\"90\"}]}");
        @SuppressWarnings("unchecked")
        List<OverseasBar> etfBars = (List<OverseasBar>) parse.invoke(null, etf, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 22),
                "xymd", "open", "high", "low", "clos", "tvol");
        assertThat(etfBars).singleElement().satisfies(bar -> assertThat(bar.volume()).isEqualTo(1234567L));
    }

    @Test
    void pagesBackwardStopsBeforeTodayAndKeepsGoingAfterAFailure() {
        when(client.fetchIndex(any(), any(), any(), any())).thenAnswer(call -> weekdays(call.getArgument(2), call.getArgument(3), 3));
        doThrow(new IllegalStateException("EGW00201")).when(client).fetchIndex(eq("N"), eq("VIX"), any(), any());
        when(client.fetchEtf(any(), any(), any(), any())).thenAnswer(call -> weekdays(call.getArgument(2), call.getArgument(3), 100));
        // 2026-10-23(금) 07:50 KST: 10-23 일봉은 받지 않고 10-22까지만 요청해요.
        var results = service("2026-10-22T22:50:00Z").backfill(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 23));

        assertThat(results).extracting(OverseasDailyService.BackfillResult::symbol)
                .containsExactly("SPX", "COMP", "NDX", "DJI", "VIX", "SOX", "USDKRW", "EWY");
        var spx = results.get(0);
        // 10-14~10-22 평일 7일을 3행씩 거꾸로: 20~22, 15~19(15,16,19), 14 → 요청 3번, 7행.
        assertThat(spx.requests()).isEqualTo(3);
        assertThat(spx.saved()).isEqualTo(7);
        assertThat(spx.newest()).isEqualTo(LocalDate.of(2026, 10, 22));
        verify(client).fetchIndex("N", "SPX", LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 22));
        assertThat(results.get(4).error()).contains("EGW00201");
        assertThat(results.get(7).saved()).isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from overseas_daily where trade_date = ?", Integer.class,
                LocalDate.of(2026, 10, 23))).isZero();
        assertThat(jdbc.queryForObject("select source_code from overseas_daily where symbol = 'USDKRW' and trade_date = ?",
                String.class, LocalDate.of(2026, 10, 22))).isEqualTo("X:FX@KRW");

        // 다시 받아도 같은 날짜는 갱신만 해요.
        service("2026-10-22T22:50:00Z").backfill(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 23));
        assertThat(jdbc.queryForObject("select count(*) from overseas_daily where symbol = 'SPX'", Integer.class)).isEqualTo(7);
    }
}
