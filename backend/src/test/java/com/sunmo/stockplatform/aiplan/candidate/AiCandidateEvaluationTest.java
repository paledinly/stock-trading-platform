/*
 * AI 후보 평가(ADR-014)의 명단·카드 고정, 응답 검사·저장(첫 판 집계), 명단 채점과 바구니 비교를 확인해요.
 * H2(PostgreSQL 흉내 모드)에 실제 마이그레이션 V50을 그대로 실행해요.
 */
package com.sunmo.stockplatform.aiplan.candidate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sunmo.stockplatform.aiplan.config.AiPlanProperties;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.closing.config.TradingCostProperties;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import com.sunmo.stockplatform.marketwide.application.VolatilityUniverse;
import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.domain.MarketType;
import com.sunmo.stockplatform.stock.domain.Stock;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AI 후보 평가 흐름 테스트예요. 대상 거래일은 2026-11-20(금), 데이터 기준일은 11-19예요. */
class AiCandidateEvaluationTest {
    private static final LocalDate TARGET = LocalDate.of(2026, 11, 20);
    // 11-19 21:00 KST(미리보기), 11-20 08:00 KST(고정), 11-20 08:30 KST(저장), 11-20 09:30 KST(장중), 11-20 21:40 KST(채점)
    private static final String PREVIEW = "2026-11-19T12:00:00Z", FREEZE = "2026-11-19T23:00:00Z",
            SUBMIT = "2026-11-19T23:30:00Z", LATE = "2026-11-20T00:30:00Z", EVENING = "2026-11-20T12:40:00Z";
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private VolatilityUniverse universe;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:aicand" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        jdbc.execute("create table stock(id bigint primary key, stock_code varchar(12), stock_name varchar(120), market varchar(20), "
                + "is_etf boolean default false, is_etn boolean default false, sector_medium_code varchar(4))");
        jdbc.execute("create table sector_code(code varchar(4) primary key, sector_name varchar(80))");
        jdbc.execute("create table daily_adjusted_candle(stock_id bigint, trade_date date, open_price numeric(20,4), "
                + "high_price numeric(20,4), low_price numeric(20,4), close_price numeric(20,4), trading_value numeric(24,4), "
                + "primary key(stock_id, trade_date))");
        jdbc.execute("create table investor_daily_flow(stock_id bigint, trade_date date, foreign_amount bigint, "
                + "institution_amount bigint, individual_amount bigint)");
        jdbc.execute("create table dart_filing(rcept_no varchar(20), rcept_dt date, stock_code varchar(12), report_nm varchar(500))");
        jdbc.execute("create table market_index_daily(index_code varchar(10), trade_date date, close numeric(14,4))");
        jdbc.execute("create table overseas_daily(symbol varchar(20), trade_date date, close numeric(18,6))");
        String migration = new ClassPathResource("db/migration/V50__create_ai_candidate_evaluation.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        for (String statement : migration.replaceAll("(?m)^\\s*--.*$", "").split(";"))
            if (!statement.isBlank())
                jdbc.execute(statement);
        jdbc.update("insert into sector_code values ('0013', '전기전자')");
        // 종목 1~150, 10-13 ~ 11-19 평일 일봉(시가 100, 종가 101, 거래대금 20억). 종목 5는 전일 거래대금이 다섯 배예요.
        for (long id = 1; id <= 150; id++)
            jdbc.update("insert into stock values (?, ?, ?, 'KOSDAQ', false, false, ?)", id, code(id), "종목" + id,
                    id == 1 ? "0013" : null);
        LocalDate day = LocalDate.of(2026, 10, 13);
        for (; !day.isAfter(LocalDate.of(2026, 11, 19)); day = day.plusDays(1)) {
            if (day.getDayOfWeek().getValue() > 5) continue;
            for (long id = 1; id <= 150; id++)
                jdbc.update("insert into daily_adjusted_candle values (?, ?, 100, 102, 99, 101, ?)", id, day,
                        id == 5 && day.equals(LocalDate.of(2026, 11, 19)) ? 10_000_000_000L : 2_000_000_000L);
        }
        jdbc.update("insert into investor_daily_flow values (1, '2026-11-19', 3800, -500, -3300)");
        jdbc.update("insert into dart_filing values ('1', '2026-11-18', ?, '단일판매ㆍ공급계약체결')", code(1));
        jdbc.update("insert into dart_filing values ('2', '2026-11-20', ?, '당일 공시는 카드에 안 나와요')", code(1));
        jdbc.update("insert into overseas_daily values ('SPX', '2026-11-18', 100), ('SPX', '2026-11-19', 101)");
        jdbc.update("insert into market_index_daily values ('1001', '2026-11-18', 1000), ('1001', '2026-11-19', 990)");
        universe = mock(VolatilityUniverse.class);
        List<VolatilityUniverse.Ranked> ranked = new ArrayList<>();
        for (long id = 1; id <= 45; id++)
            ranked.add(new VolatilityUniverse.Ranked(stock(id), 0.10 - id * 0.001));
        when(universe.ranked(any())).thenReturn(ranked);
    }

    @AfterEach
    void tearDown() { jdbc.execute("shutdown"); }

    /** 전날 저녁에는 저장하지 않는 미리보기만 주고, 대상일 07:55 뒤 처음 열면 명단 40종목과 카드가 고정돼요. */
    @Test
    void previewsTheEveningBeforeAndFreezesOnTheMorning() {
        AiCandidatePoolService.Pool preview = pools(PREVIEW).current();
        assertThat(preview.stored()).isFalse();
        assertThat(preview.targetDate()).isEqualTo(TARGET);
        assertThat(jdbc.queryForObject("select count(*) from ai_candidate_pool", Integer.class)).isZero();

        AiCandidatePoolService.Pool frozen = pools(FREEZE).current();
        assertThat(frozen.stored()).isTrue();
        assertThat(frozen.stocks()).hasSize(40);
        assertThat(frozen.stocks().get(4).valueRatio20()).isEqualByComparingTo("5");
        assertThat(frozen.cardText()).contains("targetDate: 2026-11-20", "poolId: " + frozen.poolId(), "데이터 기준일: 2026-11-19",
                "[" + code(1) + " 종목1 | KOSDAQ | 전기전자]", "S&P500 (2026-11-19): +1.0%", "코스닥: 전일 -1.0%",
                "외국인 +38", "11-18 단일판매ㆍ공급계약체결").doesNotContain("당일 공시");

        // 나중에 명단 계산이 바뀌어도 그날 저장본을 그대로 줘요.
        when(universe.ranked(any())).thenReturn(List.of(new VolatilityUniverse.Ranked(stock(99), 0.5)));
        assertThat(pools(SUBMIT).current().poolId()).isEqualTo(frozen.poolId());
    }

    /** 장 시작 전 첫 판만 집계하고, 빠진 종목·A 6개·다른 poolId는 거부해요. */
    @Test
    void countsOnlyTheFirstOnTimeEvaluation() throws Exception {
        AiCandidatePoolService.Pool pool = pools(FREEZE).current();
        AiCandidateService service = service(SUBMIT);

        AiCandidateService.SubmitResult first = service.submit(answer(pool, Set.of(1L, 2L), Set.of(3L), 40));
        assertThat(first.errors()).isEmpty();
        assertThat(first.evaluation().counted()).isTrue();
        assertThat(first.evaluation().gradeA()).isEqualTo(2);
        AiCandidateService.SubmitResult second = service.submit(answer(pool, Set.of(4L), Set.of(), 40));
        assertThat(second.evaluation().revision()).isEqualTo(2);
        assertThat(second.evaluation().counted()).isFalse();
        assertThat(service(LATE).submit(answer(pool, Set.of(4L), Set.of(), 40)).evaluation().onTime()).isFalse();

        assertThat(service.check(answer(pool, Set.of(), Set.of(), 39)).errors()).anyMatch(e -> e.contains("평가하지 않은 명단 종목"));
        assertThat(service.check(answer(pool, Set.of(1L, 2L, 3L, 4L, 5L, 6L), Set.of(), 40)).errors()).anyMatch(e -> e.contains("A등급은 5개까지"));
        assertThat(service.check(answer(pool, Set.of(), Set.of(), 40).replace(pool.poolId(), "000000000000")).errors())
                .anyMatch(e -> e.contains("poolId"));
        assertThat(service.detail(first.evaluation().id()).grades()).hasSize(40);
    }

    /** 명단 전체를 채점하고, 첫 판의 A·AVOID 바구니를 명단 평균과 비교해요. */
    @Test
    void scoresThePoolAndComparesBaskets() throws Exception {
        AiCandidatePoolService.Pool pool = pools(FREEZE).current();
        service(SUBMIT).submit(answer(pool, Set.of(1L, 2L), Set.of(3L), 40));
        service(SUBMIT).submit(answer(pool, Set.of(3L), Set.of(1L), 40)); // 두 번째 판은 집계하지 않아요.
        // 11-20: 종목 1·2는 시가 100 → 종가 105, 종목 3은 → 95, 나머지는 → 100.
        for (long id = 1; id <= 150; id++)
            jdbc.update("insert into daily_adjusted_candle values (?, '2026-11-20', 100, 106, 94, ?, 2000000000)", id,
                    id <= 2 ? 105 : id == 3 ? 95 : 100);
        assertThat(outcomes(EVENING).evaluate()).isEqualTo(40);

        AiCandidateOutcomeService.Summary summary = outcomes(EVENING).summary();
        assertThat(summary.days()).hasSize(1);
        AiCandidateOutcomeService.Day day = summary.days().get(0);
        assertThat(day.n()).isEqualTo(2);
        assertThat(day.pool()).isEqualByComparingTo("0.125");
        assertThat(day.aiA()).isEqualByComparingTo("5");
        assertThat(day.excess()).isEqualByComparingTo("4.875");
        assertThat(day.avoidExcess()).isEqualByComparingTo("-5.125");
        assertThat(day.rule()).isNotNull();
        assertThat(summary.selection().verdict()).isEqualTo("PENDING");
        assertThat(summary.filter().days()).isEqualTo(1);
    }

    /** 무작위 바구니는 같은 날짜면 같은 종목을 고르고, 규칙 바구니는 전일 거래대금 배수 상위를 골라요. */
    @Test
    void randomBasketIsReproducibleAndRuleBasketUsesValueRatio() {
        List<AiCandidateOutcomeService.Row> rows = new ArrayList<>();
        for (int i = 1; i <= 40; i++)
            rows.add(new AiCandidateOutcomeService.Row(i, i, BigDecimal.valueOf(i == 7 ? 9 : 1), i == 1 ? "A" : "C", 50,
                    BigDecimal.valueOf(i), BigDecimal.valueOf(i)));
        var first = AiCandidateOutcomeService.day(TARGET, rows);
        var again = AiCandidateOutcomeService.day(TARGET, new ArrayList<>(rows).reversed());
        assertThat(first.random()).isEqualByComparingTo(again.random());
        assertThat(first.rule()).isEqualByComparingTo("7");
        assertThat(first.topConf()).isEqualByComparingTo("1");
    }

    // ---- helpers ----

    private String answer(AiCandidatePoolService.Pool pool, Set<Long> a, Set<Long> avoid, int count) throws Exception {
        ObjectNode root = json.createObjectNode();
        root.put("schemaVersion", "ai-candidate-v1").put("targetDate", TARGET.toString()).put("poolId", pool.poolId())
                .put("analyzedAt", "2026-11-20T08:20:00+09:00");
        root.putObject("model").put("name", "ChatGPT").put("version", "test").put("promptVersion", "candidate-v1");
        root.putObject("market").put("outlook", "NEUTRAL");
        ArrayNode list = root.putArray("evaluations");
        for (AiCandidatePoolService.PoolStock stock : pool.stocks().subList(0, count)) {
            String grade = a.contains(stock.stockId()) ? "A" : avoid.contains(stock.stockId()) ? "AVOID" : "C";
            ObjectNode node = list.addObject().put("stockCode", stock.stockCode()).put("grade", grade).put("confidence", 60);
            node.putObject("rubric").put("catalyst", 1).put("flow", 1).put("trend", 1).put("overheat", 0).put("disclosureRisk", 0);
            node.putArray("reasons").add("근거");
            node.putObject("entry").put("type", grade.equals("A") ? "OPEN" : "NONE");
            if (grade.equals("A"))
                node.putObject("exit").put("stopPct", 3).put("targetPct", 6).put("timeExit", "CLOSE");
            else
                node.putNull("exit");
        }
        return json.writeValueAsString(root);
    }

    private static String code(long id) { return String.format("%05d0", id); }

    private static Stock stock(long id) {
        Stock stock = new Stock(code(id), "KR7" + code(id) + "003", "종목" + id, Market.KOSDAQ, MarketType.STOCK, false, false, Instant.now());
        try {
            var field = Stock.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(stock, id);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
        return stock;
    }

    private static ClosingTradingCalendar calendar(String nowUtc) {
        return new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse(nowUtc), ZoneOffset.UTC));
    }

    private AiCandidatePoolService pools(String now) { return new AiCandidatePoolService(jdbc, calendar(now), universe); }

    private AiCandidateService service(String now) {
        return new AiCandidateService(jdbc, json, calendar(now), pools(now));
    }

    private AiCandidateOutcomeService outcomes(String now) {
        return new AiCandidateOutcomeService(jdbc, calendar(now), new TradingCostProperties(new BigDecimal("0.015"),
                new BigDecimal("0.015"), new BigDecimal("0.20"), new BigDecimal("0.08"), new BigDecimal("0.08")),
                new AiPlanProperties(true));
    }
}
