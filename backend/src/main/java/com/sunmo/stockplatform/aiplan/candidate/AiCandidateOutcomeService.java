/*
 * AI 후보 평가(ADR-014)의 명단 전체를 장 마감 뒤 일봉으로 채점하고, 바구니 비교와 사전 등록 판정을 계산하는 파일이에요.
 * 채점 = 그날 시가 매수 → 종가 매도(%), 비용은 마감 추천과 같은 CLOSING_* 값. 갭 ±30% 초과는 SUSPECT로 빼요.
 * 바구니(동일 가중): POOL, AI_A, AI_AVOID, POOL_EX_AVOID, RANDOM(씨앗 = 날짜), RULE(전일 거래대금 20일 배수 상위), AI_TOP_CONF.
 * 판정 기준은 docs/AI_CANDIDATE_EVALUATION.md 6절이에요. 결과를 보고 바꾸지 않아요.
 */
package com.sunmo.stockplatform.aiplan.candidate;

import com.sunmo.stockplatform.aiplan.config.AiPlanProperties;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.closing.config.TradingCostProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Predicate;

/** 후보 평가 채점 담당이에요. 정답지(실제 일봉)로 명단 전체를 채점한 뒤, AI 바구니와 다른 바구니를 나란히 비교해요. */
@Service
public class AiCandidateOutcomeService {
    private static final Logger log = LoggerFactory.getLogger(AiCandidateOutcomeService.class);
    static final int MIN_DAILY_ROWS = 100;
    static final BigDecimal SUSPECT_GAP = new BigDecimal("30");
    static final int RETRY_DAYS = 7;
    /** 사전 등록 판정: 처음 40채점일, 이긴(진) 날 22일 이상. */
    public static final int GATE_DAYS = 40;
    public static final int GATE_WIN_DAYS = 22;

    /** 하루의 바구니 평균(시가→종가 %)이에요. n은 AI_A 개수(RANDOM·RULE·AI_TOP_CONF도 같은 개수)예요. */
    public record Day(LocalDate date, int poolCount, int n, int avoidCount, BigDecimal pool, BigDecimal aiA, BigDecimal aiANet,
            BigDecimal aiAvoid, BigDecimal poolExAvoid, BigDecimal random, BigDecimal rule, BigDecimal topConf) {
        public BigDecimal excess() { return minus(aiA, pool); }
        public BigDecimal avoidExcess() { return minus(aiAvoid, pool); }
    }

    /** 한 판정의 진행 상황이에요. wins는 고르기면 초과 > 0인 날, 걸러내기면 초과 < 0인 날이에요. */
    public record Gate(String verdict, int days, int wins, BigDecimal meanExcess) { }

    /** 판정 요약과 날짜별 기록이에요. 퍼센트 단위예요. */
    public record Summary(int gateDays, int gateWinDays, Gate selection, String profitVerdict, BigDecimal selectionMeanNet,
            Gate filter, BigDecimal meanVsRandom, BigDecimal meanVsRule, BigDecimal meanVsTopConf, BigDecimal meanPoolExAvoid,
            int recordedDays, List<Day> days) { }

    private final JdbcTemplate jdbc;
    private final ClosingTradingCalendar calendar;
    private final TradingCostProperties costs;
    private final AiPlanProperties settings;

    public AiCandidateOutcomeService(JdbcTemplate jdbc, ClosingTradingCalendar calendar, TradingCostProperties costs,
            AiPlanProperties settings) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.costs = costs;
        this.settings = settings;
    }

    /** 하는 일: 매일 21:35에 채점해요(AI 장전 계획 채점 21:30 뒤, 같은 켜짐 설정). */
    @Scheduled(cron = "${ai-plan.candidate-evaluation-cron:0 35 21 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        if (settings.evaluationEnabled())
            log.info("AI candidate pools evaluated: {} stocks", evaluate());
    }

    /**
     * 하는 일: 명단이 있는 날짜(오늘까지) 중 아직 채점하지 않았거나 최근 RETRY_DAYS일 안인 날의 명단 전체를 채점해요.
     * 그날 일봉이 덜 들어왔으면 건너뛰어요. 돌려주는 것: 채점한 종목 수.
     */
    @Transactional
    public int evaluate() {
        LocalDate today = calendar.today();
        List<LocalDate> dates = jdbc.query("""
                select p.target_date from ai_candidate_pool p where p.target_date <= ?
                  and (p.target_date >= ? or not exists (select 1 from ai_candidate_outcome o where o.target_date = p.target_date))
                order by p.target_date""", (rs, i) -> rs.getDate(1).toLocalDate(), Date.valueOf(today),
                Date.valueOf(today.minusDays(RETRY_DAYS)));
        int done = 0;
        for (LocalDate date : dates) {
            Integer rows = jdbc.queryForObject("select count(*) from daily_adjusted_candle where trade_date = ?", Integer.class,
                    Date.valueOf(date));
            if (rows == null || rows < MIN_DAILY_ROWS)
                continue;
            jdbc.update("delete from ai_candidate_outcome where target_date = ?", Date.valueOf(date));
            for (Long stockId : jdbc.queryForList("select stock_id from ai_candidate_pool_stock where target_date = ?",
                    Long.class, Date.valueOf(date))) {
                score(stockId, date);
                done++;
            }
        }
        return done;
    }

    private void score(long stockId, LocalDate date) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select trade_date, open_price, close_price from daily_adjusted_candle
                where stock_id = ? and trade_date between ? and ? order by trade_date desc limit 2""",
                stockId, Date.valueOf(date.minusDays(20)), Date.valueOf(date));
        boolean found = !rows.isEmpty() && ((Date) rows.get(0).get("trade_date")).toLocalDate().equals(date);
        if (!found) {
            jdbc.update("insert into ai_candidate_outcome(target_date, stock_id, status, cost_assumption, evaluated_at) "
                    + "values (?, ?, 'NO_DATA', ?, ?)", Date.valueOf(date), stockId, costText(), Timestamp.from(calendar.now()));
            return;
        }
        BigDecimal open = decimal(rows.get(0).get("open_price")), close = decimal(rows.get(0).get("close_price"));
        BigDecimal prev = rows.size() > 1 ? decimal(rows.get(1).get("close_price")) : null;
        BigDecimal gap = pct(open, prev), openClose = pct(close, open);
        boolean suspect = openClose == null || gap != null && gap.abs().compareTo(SUSPECT_GAP) > 0;
        jdbc.update("""
                insert into ai_candidate_outcome(target_date, stock_id, status, prev_close, open_price, close_price, gap_pct,
                  open_close_pct, net_open_close_pct, cost_assumption, evaluated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                Date.valueOf(date), stockId, suspect ? "SUSPECT" : "COMPLETE", prev, open, close, gap, openClose,
                net(open, close), costText(), Timestamp.from(calendar.now()));
    }

    record Row(int rank, long stockId, BigDecimal valueRatio, String grade, int confidence, BigDecimal openClose,
            BigDecimal net) { }

    /** 하는 일: 집계 판이 있는 날의 바구니 비교와 사전 등록 판정(고르기·수익·걸러내기)을 계산해요. */
    public Summary summary() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select p.target_date, p.pool_rank, p.stock_id, p.value_ratio20, g.grade, g.confidence,
                  o.open_close_pct, o.net_open_close_pct
                from ai_candidate_eval e
                  join ai_candidate_pool_stock p on p.target_date = e.target_date
                  join ai_candidate_grade g on g.eval_id = e.id and g.stock_code = p.stock_code
                  join ai_candidate_outcome o on o.target_date = p.target_date and o.stock_id = p.stock_id
                where o.status = 'COMPLETE' and""" + " " + AiCandidateService.COUNTED + " order by p.target_date, p.pool_rank");
        Map<LocalDate, List<Row>> byDate = new TreeMap<>();
        for (Map<String, Object> r : rows)
            byDate.computeIfAbsent(((Date) r.get("target_date")).toLocalDate(), d -> new ArrayList<>()).add(new Row(
                    ((Number) r.get("pool_rank")).intValue(), ((Number) r.get("stock_id")).longValue(),
                    decimal(r.get("value_ratio20")), (String) r.get("grade"), ((Number) r.get("confidence")).intValue(),
                    decimal(r.get("open_close_pct")), decimal(r.get("net_open_close_pct"))));
        List<Day> days = new ArrayList<>();
        for (var entry : byDate.entrySet())
            days.add(day(entry.getKey(), entry.getValue()));

        List<Day> selectionDays = days.stream().filter(d -> d.n() > 0).limit(GATE_DAYS).toList();
        Gate selection = gate(selectionDays.stream().map(Day::excess).toList(), BigDecimal::signum, 1);
        BigDecimal selectionNet = mean(selectionDays.stream().map(Day::aiANet).toList());
        String profit = selection.verdict().equals("PENDING") ? "PENDING"
                : selection.verdict().equals("PASS") && selectionNet != null && selectionNet.signum() > 0 ? "PASS" : "FAIL";
        List<Day> filterDays = days.stream().filter(d -> d.avoidCount() > 0).limit(GATE_DAYS).toList();
        Gate filter = gate(filterDays.stream().map(Day::avoidExcess).toList(), BigDecimal::signum, -1);
        List<Day> withA = days.stream().filter(d -> d.n() > 0).toList();
        return new Summary(GATE_DAYS, GATE_WIN_DAYS, selection, profit, selectionNet, filter,
                mean(withA.stream().map(d -> minus(d.aiA(), d.random())).toList()),
                mean(withA.stream().map(d -> minus(d.aiA(), d.rule())).toList()),
                mean(withA.stream().map(d -> minus(d.aiA(), d.topConf())).toList()),
                mean(days.stream().map(d -> minus(d.poolExAvoid(), d.pool())).toList()), days.size(), List.copyOf(days));
    }

    /** 하루의 바구니 7개를 계산해요. RANDOM은 날짜(YYYYMMDD)를 씨앗으로 명단 순서를 섞어 앞에서 n개예요. */
    static Day day(LocalDate date, List<Row> rows) {
        List<Row> a = rows.stream().filter(r -> "A".equals(r.grade())).toList();
        List<Row> avoid = rows.stream().filter(r -> "AVOID".equals(r.grade())).toList();
        int n = a.size();
        List<Row> shuffled = new ArrayList<>(rows.stream().sorted(Comparator.comparingInt(Row::rank)).toList());
        Collections.shuffle(shuffled, new Random(Long.parseLong(date.format(DateTimeFormatter.BASIC_ISO_DATE))));
        List<Row> rule = rows.stream().filter(r -> r.valueRatio() != null)
                .sorted(Comparator.comparing(Row::valueRatio).reversed().thenComparingInt(Row::rank)).limit(n).toList();
        List<Row> top = rows.stream().filter(r -> "A".equals(r.grade()) || "B".equals(r.grade()))
                .sorted(Comparator.comparingInt(Row::confidence).reversed().thenComparingInt(Row::rank)).limit(n).toList();
        return new Day(date, rows.size(), n, avoid.size(), avg(rows), avg(a), mean(a.stream().map(Row::net).toList()),
                avg(avoid), avg(rows.stream().filter(Predicate.not(avoid::contains)).toList()),
                n == 0 ? null : avg(shuffled.subList(0, n)), n == 0 ? null : avg(rule), n == 0 ? null : avg(top));
    }

    /** direction 1이면 양수인 날, -1이면 음수인 날을 이긴 날로 세요. 평균도 그 방향이어야 PASS예요. */
    private static Gate gate(List<BigDecimal> values, java.util.function.ToIntFunction<BigDecimal> sign, int direction) {
        BigDecimal mean = mean(values);
        int wins = (int) values.stream().filter(v -> v != null && sign.applyAsInt(v) == direction).count();
        String verdict = values.size() < GATE_DAYS ? "PENDING"
                : mean != null && mean.signum() == direction && wins >= GATE_WIN_DAYS ? "PASS" : "FAIL";
        return new Gate(verdict, values.size(), wins, mean);
    }

    private static BigDecimal avg(List<Row> rows) {
        return mean(rows.stream().map(Row::openClose).toList());
    }

    /** 시가 매수 → 종가 매도의 비용 뺀 수익률(%)이에요. AI 장전 계획 채점과 같은 식이에요. */
    BigDecimal net(BigDecimal open, BigDecimal close) {
        if (open == null || close == null || open.signum() <= 0)
            return null;
        BigDecimal hundred = BigDecimal.valueOf(100);
        BigDecimal buy = open.multiply(BigDecimal.ONE.add(costs.buyFeePercent().add(costs.buySlippagePercent())
                .divide(hundred, 10, RoundingMode.HALF_UP)));
        BigDecimal sell = close.multiply(BigDecimal.ONE.subtract(costs.sellFeePercent().add(costs.sellTaxPercent())
                .add(costs.sellSlippagePercent()).divide(hundred, 10, RoundingMode.HALF_UP)));
        return sell.subtract(buy).multiply(hundred).divide(buy, 6, RoundingMode.HALF_UP);
    }

    private String costText() {
        return "{\"buyFeePercent\":\"" + costs.buyFeePercent().toPlainString() + "\",\"sellFeePercent\":\""
                + costs.sellFeePercent().toPlainString() + "\",\"sellTaxPercent\":\"" + costs.sellTaxPercent().toPlainString()
                + "\",\"buySlippagePercent\":\"" + costs.buySlippagePercent().toPlainString()
                + "\",\"sellSlippagePercent\":\"" + costs.sellSlippagePercent().toPlainString() + "\"}";
    }

    static BigDecimal minus(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : left.subtract(right);
    }

    private static BigDecimal pct(BigDecimal value, BigDecimal base) {
        if (value == null || base == null || base.signum() <= 0)
            return null;
        return value.subtract(base).multiply(BigDecimal.valueOf(100)).divide(base, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        List<BigDecimal> present = values.stream().filter(v -> v != null).toList();
        if (present.isEmpty())
            return null;
        return present.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(present.size()), 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : value instanceof BigDecimal big ? big : new BigDecimal(value.toString());
    }
}
