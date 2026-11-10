/*
 * AI 실시간 감시의 연결부를 H2(PostgreSQL 흉내 모드)와 실제 마이그레이션 V47~V49로 확인해요.
 * 08:50 이후 계획 종목 구독(빈자리 → 정밀 자리 양보), 과거 5분봉 거래량 프로필, 가상 포지션 저장·갱신, 15:20 구독 해제,
 * 2단계 판정(첫 40건·15거래일 표본)을 봐요.
 */
package com.sunmo.stockplatform.aiplan.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.aiplan.application.AiPlanService;
import com.sunmo.stockplatform.aiplan.config.AiWatchProperties;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.closing.config.TradingCostProperties;
import com.sunmo.stockplatform.market.application.MarketEventGateway;
import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import com.sunmo.stockplatform.market.config.PrecisionSubscriptionProperties;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import com.sunmo.stockplatform.marketwide.application.PrecisionSubscriptionAllocator;
import com.sunmo.stockplatform.marketwide.application.PrecisionSubscriptionHistory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** AI 실시간 감시 연결부 테스트예요. */
class AiWatchServiceTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 11);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final TradingCostProperties COSTS = new TradingCostProperties(new BigDecimal("0.015"),
            new BigDecimal("0.015"), new BigDecimal("0.20"), new BigDecimal("0.08"), new BigDecimal("0.08"));
    private JdbcTemplate jdbc;
    private RealtimeSubscriptionRegistry registry;
    private PrecisionSubscriptionAllocator precision;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:aiwatch" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        jdbc.execute("create table stock(id bigint primary key, stock_code varchar(12), stock_name varchar(120), "
                + "is_etf boolean default false, is_etn boolean default false)");
        jdbc.execute("create table daily_adjusted_candle(stock_id bigint, trade_date date, open_price numeric(20,4), "
                + "high_price numeric(20,4), low_price numeric(20,4), close_price numeric(20,4), trading_value numeric(24,4), "
                + "primary key(stock_id, trade_date))");
        jdbc.execute("create table intraday_research_bar(stock_id bigint, start_time timestamp with time zone, open_price int, "
                + "high_price int, low_price int, close_price int, volume bigint, trading_value bigint, primary key(stock_id, start_time))");
        for (String file : List.of("V47__create_ai_premarket_plan.sql", "V48__ai_premarket_plan_v2.sql", "V49__create_ai_watch.sql")) {
            String migration = new ClassPathResource("db/migration/" + file).getContentAsString(StandardCharsets.UTF_8);
            for (String statement : migration.replaceAll("(?m)^\\s*--.*$", "").split(";"))
                if (!statement.isBlank()) jdbc.execute(statement);
        }
        jdbc.update("insert into stock values (1, '005930', '삼성전자', false, false)");
        jdbc.update("insert into stock values (2, '000660', 'SK하이닉스', false, false)");
        jdbc.update("insert into daily_adjusted_candle values (1, '2026-11-10', 100, 105, 99, 103, 1000000)");
        // 삼성전자 과거 5분봉: 11-10부터 거꾸로 12거래일, 09:00 칸 거래량 1,000주(전일은 2,000주), 하루 2칸.
        LocalDate day = LocalDate.of(2026, 11, 10);
        for (int i = 0; i < 12; i++, day = day.minusDays(1)) {
            while (day.getDayOfWeek().getValue() > 5) day = day.minusDays(1);
            long volume = i == 0 ? 2000 : 1000;
            jdbc.update("insert into intraday_research_bar values (1, ?, 100, 101, 99, 100, ?, ?)",
                    Timestamp.from(day.atTime(9, 0).atZone(ZONE).toInstant()), volume, volume * 100);
            jdbc.update("insert into intraday_research_bar values (1, ?, 100, 101, 99, 100, 500, 50000)",
                    Timestamp.from(day.atTime(9, 5).atZone(ZONE).toInstant()));
        }
        registry = new RealtimeSubscriptionRegistry(new RealtimeMarketProperties(true, URI.create("ws://localhost"),
                Duration.ZERO, Duration.ofHours(1), 10, 3, Duration.ofMinutes(1)));
        precision = new PrecisionSubscriptionAllocator(registry,
                new PrecisionSubscriptionProperties(true, 41, 1, Duration.ofMinutes(15), BigDecimal.TEN, LocalTime.of(23, 59, 59)),
                Mockito.mock(PrecisionSubscriptionHistory.class));
        precision.initialize();
    }

    @AfterEach
    void tearDown() { jdbc.execute("shutdown"); }

    private static ClosingTradingCalendar calendar(Instant now) {
        return new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static Instant at(String time) { return DAY.atTime(LocalTime.parse(time)).atZone(ZONE).toInstant(); }

    private AiWatchService service(Instant now) {
        AiMarketClient market = Mockito.mock(AiMarketClient.class);
        Mockito.when(market.halfSpreadPct(Mockito.anyString())).thenReturn(Optional.empty());
        return new AiWatchService(new AiWatchStore(jdbc), new ObjectMapper().findAndRegisterModules(), calendar(now), COSTS,
                new AiWatchProperties(true, null), registry, precision, market,
                new MarketEventGateway(new RealtimeMarketProperties(true, URI.create("ws://localhost"), Duration.ZERO,
                        Duration.ofHours(1), 10, 41, Duration.ofMinutes(1))));
    }

    private void submitPlan() {
        var saved = new AiPlanService(jdbc, new ObjectMapper(), calendar(Instant.parse("2026-11-10T23:10:00Z"))).submit("""
                {"schemaVersion":"ai-premarket-v2","targetDate":"2026-11-11","analysisMode":"PREMARKET","analyzedAt":null,
                 "model":{"name":"ChatGPT","version":null,"promptVersion":"premarket-v2"},
                 "market":{"outlook":"NEUTRAL","regime":"VOLATILE_MIXED","riskLevel":"HIGH"},
                 "tradeDecision":"TRADE","topPriorityStockCodes":["000660"],
                 "picks":[{"stockCode":"005930","priority":1,"status":"WATCH","reason":"r","noBuyConditions":["n"],
                           "sources":[{"title":"t"}],
                           "entryStrategy":{"conditions":[{"metric":"RVOL_5M","operator":">=","value":3}]}},
                          {"stockCode":"000660","priority":2,"status":"WATCH","reason":"r","noBuyConditions":["n"],
                           "sources":[{"title":"t"}]}]}""");
        assertThat(saved.errors()).isEmpty();
    }

    /**
     * 기대: 08:50 전에는 아무것도 하지 않아요. 08:55에 최우선 종목(SK하이닉스)이 먼저 빈자리를 받고, 삼성전자는 정밀 후보 자리를 양보받아요.
     * 관심종목은 그대로예요. 규칙 A: 삼성전자는 조건이 있어 감시, SK하이닉스는 조건이 없어 UNSUPPORTED예요. 15:20에 AI 구독을 풀어요.
     */
    @Test
    void subscribesPlanPicksInPriorityOrderAndReleasesThemAfterTheClose() {
        submitPlan();
        registry.add("035720", RealtimeSubscriptionRegistry.Source.WATCHLIST);
        precision.reconcile(List.of(new PrecisionSubscriptionAllocator.Candidate("A00001", BigDecimal.ONE)));
        assertThat(registry.remaining()).isEqualTo(1);

        AiWatchService watch = service(at("08:55:00"));
        watch.sync(at("08:40:00"));
        assertThat(registry.all()).doesNotContain("005930", "000660");
        watch.sync(at("08:55:00"));

        assertThat(registry.entries()).containsEntry("000660", java.util.Set.of(RealtimeSubscriptionRegistry.Source.AI_PLAN))
                .containsEntry("005930", java.util.Set.of(RealtimeSubscriptionRegistry.Source.AI_PLAN))
                .containsKey("035720").doesNotContainKey("A00001");
        var view = watch.view(DAY);
        assertThat(view.live()).isTrue();
        assertThat(view.session().subscribedCount()).isEqualTo(2);
        assertThat(view.session().positionAmount()).isEqualByComparingTo("10000000");
        assertThat(view.stocks()).extracting(AiWatchEngine.StockView::stockCode).containsExactly("000660", "005930");
        assertThat(view.stocks()).extracting(AiWatchEngine.StockView::stateA)
                .containsExactly(WatchRules.State.UNSUPPORTED, WatchRules.State.WATCH);

        watch.evaluate(at("15:20:01"));

        assertThat(registry.all()).containsExactly("035720");
        assertThat(watch.view(DAY).live()).isFalse();
        assertThat(watch.view(DAY).session().endedAt()).isEqualTo(at("15:20:01"));
    }

    /** 기대: 거래량 프로필은 5분봉이 있는 날(12일)로 칸 평균을 내고, 전일 5분 평균 거래대금과 전일 고가·종가를 읽어요. */
    @Test
    void buildsTheVolumeProfileFromResearchBars() {
        var days = new java.util.ArrayList<LocalDate>();
        var calendar = calendar(at("08:55:00"));
        for (LocalDate d = DAY; days.size() < 20; ) days.add(d = calendar.previousTradingDay(d));

        var profile = new AiWatchStore(jdbc).profile(1, days, DAY);

        assertThat(profile.days()).isEqualTo(12);
        // 09:00 칸: (2,000 + 1,000 × 11) ÷ 12
        assertThat(profile.slotVolume().get(LocalTime.of(9, 0))).isEqualByComparingTo("1083.3333");
        assertThat(profile.slotVolume().get(LocalTime.of(9, 5))).isEqualByComparingTo("500");
        // 전일(11-10) 5분 평균 거래대금: (200,000 + 50,000) ÷ 2
        assertThat(profile.previousBarTurnover()).isEqualByComparingTo("125000");
        assertThat(profile.previousHigh()).isEqualByComparingTo("105");
        assertThat(profile.previousClose()).isEqualByComparingTo("103");
    }

    /**
     * 기대: 규칙 P·E1 판정은 날짜 순으로 쌓아 40건·15거래일이 처음 된 날까지의 표본으로만 해요.
     * 3종목 × 14일(42건)은 PENDING, 15일째에 PASS(비용 뺀 평균 +0.5%, 시가 매수 +0.1%보다 나음), 그 뒤의 큰 손실은 판정을 바꾸지 않아요.
     */
    @Test
    void judgesPhaseTwoOnTheFirstSampleThatReachesTheGate() {
        submitPlan();
        long planId = jdbc.queryForObject("select id from ai_premarket_plan", Long.class);
        var store = new AiWatchStore(jdbc);
        long[] picks = new long[3];
        for (int i = 0; i < 3; i++) {
            jdbc.update("insert into ai_premarket_pick(plan_id, kind, stock_id, stock_code, stock_name, priority) values (?, 'PICK', 1, ?, ?, ?)",
                    planId, "90000" + i, "종목" + i, 10 + i);
            picks[i] = jdbc.queryForObject("select id from ai_premarket_pick where stock_code = ?", Long.class, "90000" + i);
            jdbc.update("insert into ai_premarket_outcome(pick_id, trade_date, status, net_open_close_pct, evaluated_at) "
                    + "values (?, '2026-11-11', 'COMPLETE', 0.1, now())", picks[i]);
        }
        LocalDate day = LocalDate.of(2026, 12, 1);
        for (int d = 0; d < 16; d++, day = day.plusDays(1)) {
            for (int i = 0; i < 3; i++)
                store.position(day, position(picks[i], d == 15 ? "-9" : "0.5"), "{}");
            if (d == 13) assertThat(service(at("12:00:00")).summary().verdict()).isEqualTo("PENDING");
        }

        var summary = service(at("12:00:00")).summary();

        assertThat(summary.verdict()).isEqualTo("PASS");
        assertThat(summary.gate().trades()).isEqualTo(45);
        assertThat(summary.gate().days()).isEqualTo(15);
        assertThat(summary.gate().meanNetPct()).isEqualByComparingTo("0.5");
        assertThat(summary.gate().meanVsOpenPct()).isEqualByComparingTo("0.4");
        assertThat(summary.groups()).filteredOn(g -> g.rule().equals("P") && g.exit().equals("E1") && g.scope().equals("ALL"))
                .singleElement().satisfies(g -> assertThat(g.trades()).isEqualTo(48));
    }

    /** 기대: 같은 날·종목·규칙의 포지션을 다시 저장하면 새 줄이 아니라 청산 값만 고쳐요. */
    @Test
    void updatesExitsInPlace() {
        submitPlan();
        long pick = jdbc.queryForObject("select id from ai_premarket_pick where stock_code = '005930'", Long.class);
        var store = new AiWatchStore(jdbc);
        var open = new AiWatchEngine.PositionView(pick, "005930", "P", false, at("09:20:03"), at("09:20:10"), at("09:20:10"),
                new BigDecimal("100"), new BigDecimal("0.05"), "ORDERBOOK", new BigDecimal("100.05"), 99950, false,
                new BigDecimal("98.5"), null, null, new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("99"), false);
        store.position(DAY, open, "{}");
        var exit = new AiWatchEngine.Exit(new BigDecimal("98.45"), at("10:00:00"), "STOP", new BigDecimal("-1.9"));
        store.position(DAY, new AiWatchEngine.PositionView(pick, "005930", "P", false, at("09:20:03"), at("09:20:10"),
                at("09:20:10"), new BigDecimal("100"), new BigDecimal("0.05"), "ORDERBOOK", new BigDecimal("100.05"), 99950,
                false, new BigDecimal("98.5"), exit, null, new BigDecimal("101"), new BigDecimal("98.4"), new BigDecimal("99"),
                false), "{}");

        assertThat(store.positions(DAY)).singleElement().satisfies(p -> {
            assertThat(p.e1().price()).isEqualByComparingTo("98.45");
            assertThat(p.e1().reason()).isEqualTo("STOP");
            assertThat(p.e1().at()).isEqualTo(at("10:00:00"));
            assertThat(p.e2()).isNull();
            assertThat(p.maxPrice()).isEqualByComparingTo("101");
        });
    }

    private static AiWatchEngine.PositionView position(long pick, String net) {
        var exit = new AiWatchEngine.Exit(BigDecimal.TEN, Instant.parse("2026-12-01T06:19:00Z"), "CLOSE", new BigDecimal(net));
        return new AiWatchEngine.PositionView(pick, "900000", "P", true, Instant.parse("2026-12-01T00:20:00Z"),
                Instant.parse("2026-12-01T00:20:01Z"), Instant.parse("2026-12-01T00:20:01Z"), BigDecimal.TEN,
                new BigDecimal("0.05"), "FALLBACK", BigDecimal.TEN, 1, false, BigDecimal.ONE, exit, exit, BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.TEN, false);
    }
}
