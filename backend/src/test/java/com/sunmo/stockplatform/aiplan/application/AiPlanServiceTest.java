/*
 * AI 장전 계획이 원본 그대로 판(revision)으로 쌓이고, 장 마감 뒤 일봉으로 채점되며, 1단계 기준이 계산되는지 확인해요.
 * H2(PostgreSQL 흉내 모드)에 실제 마이그레이션 V47·V48을 그대로 실행해요.
 */
package com.sunmo.stockplatform.aiplan.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.aiplan.config.AiPlanProperties;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.closing.config.TradingCostProperties;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** AI 장전 계획 저장·채점 테스트예요. */
class AiPlanServiceTest {
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:aiplan" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(db);
        jdbc.execute("create table stock(id bigint primary key, stock_code varchar(12), stock_name varchar(120), "
                + "is_etf boolean default false, is_etn boolean default false)");
        jdbc.execute("create table daily_adjusted_candle(stock_id bigint, trade_date date, open_price numeric(20,4), "
                + "high_price numeric(20,4), low_price numeric(20,4), close_price numeric(20,4), trading_value numeric(24,4), "
                + "primary key(stock_id, trade_date))");
        for (String file : List.of("V47__create_ai_premarket_plan.sql", "V48__ai_premarket_plan_v2.sql")) {
            String migration = new ClassPathResource("db/migration/" + file).getContentAsString(StandardCharsets.UTF_8);
            for (String statement : migration.replaceAll("(?m)^\s*--.*$", "").split(";"))
                if (!statement.isBlank())
                    jdbc.execute(statement);
        }
        // 종목 1~150은 시장 평균용(시가 100 → 종가 101, +1%)이고 종목 1이 삼성전자예요. 종목 151은 ETF라 평균에서 빠져요.
        // 10-12 ~ 11-11 평일 23일이라 11-11에는 직전 20행이 모두 있어요.
        for (long id = 1; id <= 151; id++)
            jdbc.update("insert into stock values (?, ?, ?, ?, false)", id, String.format("%06d", id == 1 ? 5930 : id + 100000),
                    id == 1 ? "삼성전자" : "종목" + id, id == 151);
        jdbc.update("insert into stock values (900, '000660', 'SK하이닉스', false, false)");
        LocalDate day = LocalDate.of(2026, 10, 12);
        for (int i = 0; i < 23; i++, day = day.plusDays(1)) {
            while (day.getDayOfWeek().getValue() > 5) day = day.plusDays(1);
            for (long id = 1; id <= 151; id++)
                jdbc.update("insert into daily_adjusted_candle values (?, ?, 100, 102, 99, 101, 2000000000)", id, day);
        }
        // 11-11(수): 삼성전자 시가 100 → 종가 104(+4%), SK하이닉스 시가 100 → 종가 98(−2%). 전날 종가는 모두 101.
        jdbc.update("update daily_adjusted_candle set close_price = 104, high_price = 105 where stock_id = 1 and trade_date = '2026-11-11'");
        for (LocalDate d : List.of(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 11)))
            jdbc.update("insert into daily_adjusted_candle values (900, ?, 100, 101, 97, ?, 2000000000)", d, d.getDayOfMonth() == 11 ? 98 : 101);
    }

    @AfterEach
    void tearDown() { jdbc.execute("shutdown"); }

    private static ClosingTradingCalendar calendar(String nowUtc) {
        return new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse(nowUtc), ZoneOffset.UTC));
    }

    private AiPlanService plans(String nowUtc) { return new AiPlanService(jdbc, new ObjectMapper(), calendar(nowUtc)); }

    private AiPlanOutcomeService outcomes(String nowUtc) {
        return new AiPlanOutcomeService(jdbc, calendar(nowUtc), new TradingCostProperties(new BigDecimal("0.015"),
                new BigDecimal("0.015"), new BigDecimal("0.20"), new BigDecimal("0.08"), new BigDecimal("0.08")),
                new AiPlanProperties(true));
    }

    private static final String PLAN = """
            {"schemaVersion":"ai-premarket-v1","targetDate":"2026-11-11","analyzedAt":"2026-11-11T07:50:00+09:00",
             "model":{"name":"ChatGPT","version":"test-model","promptVersion":"premarket-v1"},
             "market":{"outlook":"BULLISH"},"tradeDecision":"TRADE",
             "picks":[{"stockCode":"005930","priority":1,"reason":"%s","noBuyCondition":"n",
                       "sources":[{"title":"t","url":"https://x"}]}],
             "avoid":[{"stockCode":"000660","reason":"과열"}]}""";

    /** 기대: 같은 날짜를 다시 넣으면 2판이 되고, 원본 글자는 받은 그대로예요. 장 시작 뒤 3판은 집계에서 빠져요(scored=false). */
    @Test
    void storesRevisionsAsReceived() {
        var first = plans("2026-11-10T22:50:00Z").submit(PLAN.formatted("첫 판"));
        var second = plans("2026-11-10T23:10:00Z").submit(PLAN.formatted("둘째 판"));
        var late = plans("2026-11-11T00:30:00Z").submit(PLAN.formatted("장중 판"));

        assertThat(first.saved()).isTrue();
        assertThat(second.plan().revision()).isEqualTo(2);
        assertThat(late.plan().onTime()).isFalse();
        var detail = plans("2026-11-11T01:00:00Z").detail(late.plan().id());
        assertThat(detail.rawJson()).isEqualTo(PLAN.formatted("장중 판"));
        assertThat(detail.revisions()).extracting(AiPlanService.PlanSummary::revision).containsExactly(3, 2, 1);
        assertThat(detail.revisions()).extracting(AiPlanService.PlanSummary::scored).containsExactly(false, true, false);
        assertThat(plans("2026-11-11T01:00:00Z").list(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)))
                .singleElement().extracting(AiPlanService.PlanSummary::revision).isEqualTo(3);
    }

    /** 기대: 검사에 실패하면 아무것도 저장하지 않아요. */
    @Test
    void savesNothingWhenInvalid() {
        var result = plans("2026-11-10T22:50:00Z").submit(PLAN.formatted("x").replace("005930", "123456"));

        assertThat(result.saved()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("123456"));
        assertThat(jdbc.queryForObject("select count(*) from ai_premarket_plan", Integer.class)).isZero();
    }

    /**
     * 기대: 장 마감 뒤 채점은 장 시작 전 마지막 판(2판)만 써요. 삼성전자 시가→종가 +4%, 시장 평균 +1.02%
     * (ETF 제외 150종목, 삼성전자 포함), 초과 +2.98%p. 비용(매수 0.095%, 매도 0.295%)을 빼면 약 +3.6%. SK하이닉스(매수 금지)는 −2%로 시장보다 −3.02%p예요.
     */
    @Test
    void scoresTheLastOnTimeRevisionAgainstTheUniverse() {
        plans("2026-11-10T22:50:00Z").submit(PLAN.formatted("첫 판"));
        long scored = plans("2026-11-10T23:10:00Z").submit(PLAN.formatted("둘째 판")).plan().id();
        plans("2026-11-11T00:30:00Z").submit(PLAN.formatted("장중 판"));

        int done = outcomes("2026-11-11T12:30:00Z").evaluate();

        assertThat(done).isEqualTo(2);
        var picks = plans("2026-11-11T12:30:00Z").detail(scored).picks();
        var samsung = picks.stream().filter(p -> p.kind().equals("PICK")).findFirst().orElseThrow().outcome();
        assertThat(samsung.status()).isEqualTo("COMPLETE");
        assertThat(samsung.universeCount()).isEqualTo(150);
        assertThat(samsung.openClosePct()).isEqualByComparingTo("4");
        assertThat(samsung.universeOpenClosePct()).isEqualByComparingTo("1.02");
        assertThat(samsung.excessOpenClosePct()).isEqualByComparingTo("2.98");
        assertThat(samsung.gapPct()).isEqualByComparingTo("-0.990099");
        assertThat(samsung.netOpenClosePct().doubleValue()).isBetween(3.58, 3.60);
        var hynix = picks.stream().filter(p -> p.kind().equals("AVOID")).findFirst().orElseThrow().outcome();
        assertThat(hynix.excessOpenClosePct()).isEqualByComparingTo("-3.02");

        var summary = outcomes("2026-11-11T12:30:00Z").summary();
        assertThat(summary.scoredDays()).isEqualTo(1);
        assertThat(summary.gateVerdict()).isEqualTo("PENDING");
        assertThat(summary.meanExcess()).isEqualByComparingTo("2.98");
        assertThat(summary.meanAvoidExcess()).isEqualByComparingTo("-3.02");
    }

    /** 기대: 종목 없이 NO_TRADE로 판단한 날도 그날 시장 평균(+1.02%)이 저장되어 '매매 안 함' 날로 집계돼요. */
    @Test
    void countsNoTradeDaysWithoutStocks() {
        String noTrade = """
                {"schemaVersion":"ai-premarket-v1","targetDate":"2026-11-11","analyzedAt":"2026-11-11T07:50:00+09:00",
                 "model":{"name":"ChatGPT","version":"m","promptVersion":"premarket-v1"},
                 "market":{"outlook":"BEARISH"},"tradeDecision":"NO_TRADE","picks":[]}""";
        assertThat(plans("2026-11-10T23:10:00Z").submit(noTrade).saved()).isTrue();

        assertThat(outcomes("2026-11-11T12:30:00Z").evaluate()).isZero();
        var summary = outcomes("2026-11-11T12:30:00Z").summary();
        assertThat(summary.noTradeDays()).isEqualTo(1);
        assertThat(summary.noTradeUniverseOpenClose()).isEqualByComparingTo("1.02");
        assertThat(summary.scoredDays()).isZero();
    }

    /** 기대: 그날 일봉이 아직 없으면(시장 평균 종목 100개 미만) 채점을 미뤄요. */
    @Test
    void waitsForDailyCandles() {
        jdbc.update("delete from daily_adjusted_candle where trade_date = '2026-11-11'");
        plans("2026-11-10T23:10:00Z").submit(PLAN.formatted("판"));

        assertThat(outcomes("2026-11-11T12:30:00Z").evaluate()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ai_premarket_outcome", Integer.class)).isZero();
    }

    /**
     * 기대: v2의 WATCH_ONLY 날도 종목을 채점해 채점일로 세고, 최우선 감시 종목(삼성전자)의 초과를 따로 집계해요.
     * 다음 날 DATA_INSUFFICIENT는 '매매 안 함' 날이에요.
     */
    @Test
    void scoresWatchOnlyDaysAndTopPriorityPicks() {
        String watchOnly = """
                {"schemaVersion":"ai-premarket-v2","targetDate":"2026-11-11","analysisMode":"PREMARKET","analyzedAt":null,
                 "model":{"name":"ChatGPT","version":null,"promptVersion":"premarket-v2"},
                 "market":{"outlook":"NEUTRAL","regime":"VOLATILE_MIXED","riskLevel":"HIGH"},
                 "tradeDecision":"WATCH_ONLY","topPriorityStockCodes":["005930"],
                 "picks":[{"stockCode":"005930","priority":1,"status":"WATCH","reason":"r","noBuyConditions":["n"],
                           "sources":[{"title":"t"}]},
                          {"stockCode":"000660","priority":2,"status":"WATCH","reason":"r","noBuyConditions":["n"],
                           "sources":[{"title":"t"}]}]}""";
        var saved = plans("2026-11-10T23:10:00Z").submit(watchOnly);
        assertThat(saved.errors()).isEmpty();
        assertThat(saved.plan().analyzedAt()).isNull();
        assertThat(saved.plan().marketRegime()).isEqualTo("VOLATILE_MIXED");

        assertThat(outcomes("2026-11-11T12:30:00Z").evaluate()).isEqualTo(2);
        var summary = outcomes("2026-11-11T12:30:00Z").summary();

        assertThat(summary.scoredDays()).isEqualTo(1);
        assertThat(summary.topCount()).isEqualTo(1);
        assertThat(summary.meanTopExcess()).isEqualByComparingTo("2.98");
        // 그날 평균: (+4% + −2%) / 2 = +1%, 시장 +1.02% → 초과 −0.02%p
        assertThat(summary.meanExcess()).isEqualByComparingTo("-0.02");
        var detail = plans("2026-11-11T12:30:00Z").detail(saved.plan().id());
        assertThat(detail.picks()).filteredOn(AiPlanService.PickView::topPriority)
                .extracting(AiPlanService.PickView::stockCode).containsExactly("005930");
    }
}
