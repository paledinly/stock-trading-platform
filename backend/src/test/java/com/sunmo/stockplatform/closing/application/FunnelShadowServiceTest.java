/*
 * 깔때기 그림자 시세 기록이 Broad 후보와 정밀 종목을 한 번씩만 모아 저장하는지 확인해요.
 */
package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 깔때기 그림자 시세 기록을 점검하는 테스트예요. */
class FunnelShadowServiceTest {
    private DriverManagerDataSource db;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        db = new DriverManagerDataSource("jdbc:h2:mem:funnel" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(db);
        jdbc.execute("create table stock(id bigint primary key, stock_code varchar(12))");
        jdbc.execute("create table market_broad_snapshot(session_date date, stock_id bigint)");
        jdbc.execute("create table precision_subscription_session(session_date date, stock_code varchar(12))");
        jdbc.execute("create table funnel_shadow_quote(session_date date, stock_id bigint, stock_code varchar(12), "
                + "in_broad boolean, in_precision boolean, price numeric, change_rate numeric, accumulated_trading_value numeric, "
                + "received_at timestamp with time zone, error varchar(200), primary key(session_date, stock_id))");
        jdbc.execute("insert into stock values (1, '000001'), (2, '000002'), (3, '000003')");
        // 종목 1은 Broad만(두 번 찍힘), 종목 2는 Broad와 정밀, 종목 3은 정밀만이에요. 다른 날 기록은 세지 않아요.
        jdbc.execute("insert into market_broad_snapshot values ('2026-10-02', 1), ('2026-10-02', 1), ('2026-10-02', 2), ('2026-10-01', 3)");
        jdbc.execute("insert into precision_subscription_session values ('2026-10-02', '000002'), ('2026-10-02', '000003')");
    }

    @AfterEach
    void tearDown() { jdbc.execute("shutdown"); }

    /**
     * 기대: 대상 3종목, 각 1번씩 조회·저장, 단계 표시(in_broad·in_precision)가 맞고, 현재가가 없는 종목은 오류로 남아요.
     */
    @Test
    void recordsEveryFunnelStockOnceWithStageFlags() throws Exception {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse("2026-10-02T06:20:30Z"), ZoneOffset.UTC));
        var json = new ObjectMapper();
        List<String> calls = new ArrayList<>();
        var service = new FunnelShadowService(true, mock(KisProperties.class), calendar, jdbc, code -> {
            calls.add(code);
            try {
                return json.readTree(code.equals("000003") ? "{\"stck_prpr\":\"\"}"
                        : "{\"stck_prpr\":\"1000\",\"prdy_ctrt\":\"3.5\",\"acml_tr_pbmn\":\"5000000000\"}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        var result = service.record();

        assertThat(calls).containsExactlyInAnyOrder("000001", "000002", "000003");
        assertThat(result.targets()).isEqualTo(3);
        assertThat(result.failed()).isEqualTo(1);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select stock_code, in_broad, in_precision, price, error from funnel_shadow_quote order by stock_code");
        assertThat(rows).extracting(row -> row.get("STOCK_CODE") + ":" + row.get("IN_BROAD") + ":" + row.get("IN_PRECISION"))
                .containsExactly("000001:true:false", "000002:true:true", "000003:false:true");
        assertThat(rows.get(2).get("ERROR")).isEqualTo("NO_PRICE");
    }
}
