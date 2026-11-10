/*
 * AI 장전 계획의 종목을 장 마감 뒤 실제 일봉으로 채점하고, 1단계 통과 기준(ADR-012)의 진행 상황을 계산하는 파일이에요.
 * 채점 기준은 그날 시가 매수 → 종가 매도예요. 같은 날 거래대금 10억 원 이상 종목 전체 평균(시장 평균)과 비교해요.
 * 매일 21:30에 자동으로 돌고(수정주가 일봉 갱신 18:30 뒤), API로 바로 돌릴 수도 있어요.
 * 결과는 ai_premarket_outcome에만 쓰고, AI가 적은 원본은 건드리지 않아요.
 */
package com.sunmo.stockplatform.aiplan.application;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** AI 계획 채점 담당이에요. 시험이 끝난 뒤(장 마감 뒤) 정답지(실제 일봉)를 들고 답안지를 채점하는 선생님이에요. */
@Service
public class AiPlanOutcomeService {
    private static final Logger log = LoggerFactory.getLogger(AiPlanOutcomeService.class);
    // 시장 평균에 넣는 종목: 직전 20거래일 평균 거래대금 10억 원 이상(연구 기준과 같아요).
    static final BigDecimal UNIVERSE_MIN_VALUE = new BigDecimal("1000000000");
    // 이보다 작으면 그날 일봉이 아직 덜 들어온 것으로 보고 채점을 미뤄요.
    static final int MIN_UNIVERSE = 100;
    // 전일 종가 대비 시가가 이만큼 넘게 다르면 분할·병합 같은 기업행사일 수 있어 SUSPECT로 표시해요.
    static final BigDecimal SUSPECT_GAP = new BigDecimal("30");
    // NO_DATA(일봉 없음)는 이 기간 동안 매번 다시 채점해요(늦게 들어오는 일봉 대비).
    static final int RETRY_DAYS = 7;
    // 1단계 통과 기준(ADR-012): 처음 20개 채점일에서 평균 초과수익 > 0, 그리고 12일 이상 시장 평균보다 나음.
    public static final int GATE_DAYS = 20;
    public static final int GATE_WIN_DAYS = 12;

    /** 1단계 기준 진행 상황과 누적 통계예요. 퍼센트 단위예요. */
    public record Summary(int scoredDays, int gateDays, int gateWinDaysRequired, String gateVerdict,
            BigDecimal gateMeanExcess, int gateWinDays, BigDecimal meanPickOpenClose, BigDecimal meanPickNet,
            BigDecimal meanUniverseOpenClose, BigDecimal meanExcess, int winDays, int pickCount,
            BigDecimal meanAvoidExcess, int avoidCount, int noTradeDays, BigDecimal noTradeUniverseOpenClose,
            BigDecimal tradeUniverseOpenClose, List<Day> days) { }

    /** 채점일 하루예요. excess = 그날 AI 종목 평균 − 시장 평균이에요. */
    public record Day(LocalDate date, String tradeDecision, int picks, BigDecimal pickOpenClose, BigDecimal pickNet,
            BigDecimal universeOpenClose, BigDecimal excess, BigDecimal avoidExcess) { }

    private final JdbcTemplate jdbc;
    private final ClosingTradingCalendar calendar;
    private final TradingCostProperties costs;
    private final AiPlanProperties settings;

    /** 받는 것: DB 접근 도구, 거래일 달력, 거래비용 설정(마감 추천과 같은 값), 자동 채점 설정. */
    public AiPlanOutcomeService(JdbcTemplate jdbc, ClosingTradingCalendar calendar, TradingCostProperties costs,
            AiPlanProperties settings) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.costs = costs;
        this.settings = settings;
    }

    /** 하는 일: 매일 정해진 시각(기본 21:30)에 채점해요. */
    @Scheduled(cron = "${ai-plan.evaluation-cron:0 30 21 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        if (settings.evaluationEnabled())
            log.info("AI premarket plans evaluated: {} picks", evaluate());
    }

    /**
     * 하는 일: 채점할 판(날짜별로 장 시작 전에 들어온 마지막 판)의 종목을 채점해요.
     * 아직 채점하지 않은 날짜와, 최근 RETRY_DAYS일 안의 날짜를 다시 봐요. 그날 일봉이 덜 들어왔으면 건너뛰어요.
     * 돌려주는 것: 채점한 종목 수.
     */
    @Transactional
    public int evaluate() {
        LocalDate today = calendar.today();
        List<Map<String, Object>> plans = jdbc.queryForList("""
                select p.id, p.target_date from ai_premarket_plan p
                where p.on_time and p.target_date <= ?
                  and p.revision = (select max(q.revision) from ai_premarket_plan q where q.target_date = p.target_date and q.on_time)
                  and (p.target_date >= ?
                       or not exists (select 1 from ai_premarket_universe u where u.trade_date = p.target_date)
                       or exists (select 1 from ai_premarket_pick k where k.plan_id = p.id
                         and not exists (select 1 from ai_premarket_outcome o where o.pick_id = k.id)))
                order by p.target_date""", Date.valueOf(today), Date.valueOf(today.minusDays(RETRY_DAYS)));
        int done = 0;
        for (Map<String, Object> plan : plans) {
            LocalDate date = ((Date) plan.get("target_date")).toLocalDate();
            Universe universe = universe(date);
            if (universe.count() < MIN_UNIVERSE)
                continue;
            jdbc.update("delete from ai_premarket_universe where trade_date = ?", Date.valueOf(date));
            jdbc.update("insert into ai_premarket_universe(trade_date, open_close_pct, gap_pct, stock_count, evaluated_at) "
                    + "values (?, ?, ?, ?, ?)", Date.valueOf(date), universe.openClose(), universe.gap(), universe.count(),
                    Timestamp.from(calendar.now()));
            List<Map<String, Object>> picks = jdbc.queryForList(
                    "select id, stock_id from ai_premarket_pick where plan_id = ?", plan.get("id"));
            for (Map<String, Object> pick : picks) {
                score(((Number) pick.get("id")).longValue(), ((Number) pick.get("stock_id")).longValue(), date, universe);
                done++;
            }
        }
        return done;
    }

    private record Universe(BigDecimal openClose, BigDecimal gap, int count) { }

    /** 그날 시장 평균(동일 가중)이에요. 직전 20행 평균 거래대금 10억 원 이상, ETF·ETN 제외, 시가·전일 종가가 있는 종목이에요. */
    private Universe universe(LocalDate date) {
        Map<String, Object> row = jdbc.queryForMap("""
                select avg((close_price / open_price - 1) * 100) oc, avg((open_price / prev_close - 1) * 100) gap, count(*) n
                from (select d.trade_date, d.open_price, d.close_price,
                        lag(d.close_price) over (partition by d.stock_id order by d.trade_date) prev_close,
                        avg(d.trading_value) over (partition by d.stock_id order by d.trade_date
                          rows between 20 preceding and 1 preceding) val20,
                        count(*) over (partition by d.stock_id order by d.trade_date
                          rows between 20 preceding and 1 preceding) rows20
                      from daily_adjusted_candle d join stock s on s.id = d.stock_id
                      where not s.is_etf and not s.is_etn and d.trade_date between ? and ?) x
                where trade_date = ? and rows20 = 20 and val20 >= ? and open_price > 0 and prev_close > 0
                  and abs(open_price / prev_close - 1) < 0.3""",
                Date.valueOf(date.minusDays(45)), Date.valueOf(date), Date.valueOf(date), UNIVERSE_MIN_VALUE);
        return new Universe(decimal(row.get("oc")), decimal(row.get("gap")), ((Number) row.get("n")).intValue());
    }

    /** 종목 하나를 채점해 결과를 바꿔 써요. 그날 일봉이 없으면 NO_DATA, 갭이 너무 크면 SUSPECT예요. */
    private void score(long pickId, long stockId, LocalDate date, Universe universe) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select trade_date, open_price, high_price, low_price, close_price from daily_adjusted_candle
                where stock_id = ? and trade_date between ? and ? order by trade_date desc limit 2""",
                stockId, Date.valueOf(date.minusDays(20)), Date.valueOf(date));
        jdbc.update("delete from ai_premarket_outcome where pick_id = ?", pickId);
        boolean found = !rows.isEmpty() && ((Date) rows.get(0).get("trade_date")).toLocalDate().equals(date);
        if (!found) {
            jdbc.update("""
                    insert into ai_premarket_outcome(pick_id, trade_date, status, universe_open_close_pct, universe_gap_pct,
                      universe_count, cost_assumption, evaluated_at) values (?, ?, 'NO_DATA', ?, ?, ?, ?, ?)""",
                    pickId, Date.valueOf(date), universe.openClose(), universe.gap(), universe.count(), costText(),
                    Timestamp.from(calendar.now()));
            return;
        }
        Map<String, Object> day = rows.get(0);
        BigDecimal open = decimal(day.get("open_price")), high = decimal(day.get("high_price"));
        BigDecimal low = decimal(day.get("low_price")), close = decimal(day.get("close_price"));
        BigDecimal prev = rows.size() > 1 ? decimal(rows.get(1).get("close_price")) : null;
        BigDecimal gap = pct(open, prev), openClose = pct(close, open);
        boolean suspect = gap != null && gap.abs().compareTo(SUSPECT_GAP) > 0;
        jdbc.update("""
                insert into ai_premarket_outcome(pick_id, trade_date, status, prev_close, open_price, high_price, low_price,
                  close_price, gap_pct, open_close_pct, high_pct, low_pct, close_close_pct, net_open_close_pct,
                  universe_open_close_pct, universe_gap_pct, universe_count, excess_open_close_pct, cost_assumption, evaluated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                pickId, Date.valueOf(date), suspect ? "SUSPECT" : "COMPLETE", prev, open, high, low, close, gap, openClose,
                pct(high, open), pct(low, open), pct(close, prev), net(open, close), universe.openClose(), universe.gap(),
                universe.count(), openClose == null || universe.openClose() == null ? null : openClose.subtract(universe.openClose()),
                costText(), Timestamp.from(calendar.now()));
    }

    /**
     * 하는 일: 1단계 통과 기준의 진행 상황과 누적 통계를 계산해요.
     * 채점일은 TRADE로 판단한 날 중 COMPLETE 종목이 하나 이상 있는 날이에요. NO_TRADE 날은 따로 세요.
     */
    public Summary summary() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select p.target_date, p.trade_decision, k.kind, o.open_close_pct, o.net_open_close_pct,
                  o.universe_open_close_pct, o.excess_open_close_pct
                from ai_premarket_plan p join ai_premarket_pick k on k.plan_id = p.id
                  join ai_premarket_outcome o on o.pick_id = k.id
                where p.on_time and o.status = 'COMPLETE'
                  and p.revision = (select max(q.revision) from ai_premarket_plan q where q.target_date = p.target_date and q.on_time)
                order by p.target_date""");
        Map<LocalDate, List<Map<String, Object>>> byDate = new TreeMap<>();
        for (Map<String, Object> row : rows)
            byDate.computeIfAbsent(((Date) row.get("target_date")).toLocalDate(), d -> new ArrayList<>()).add(row);
        // 종목 없이 NO_TRADE로 판단한 날은 채점 행이 없어서, 채점 때 저장한 그날 시장 평균을 써요.
        List<BigDecimal> extraNoTrade = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select p.target_date, u.open_close_pct from ai_premarket_plan p
                  join ai_premarket_universe u on u.trade_date = p.target_date
                where p.on_time and p.trade_decision = 'NO_TRADE'
                  and p.revision = (select max(q.revision) from ai_premarket_plan q where q.target_date = p.target_date and q.on_time)
                """))
            if (!byDate.containsKey(((Date) row.get("target_date")).toLocalDate()))
                extraNoTrade.add(decimal(row.get("open_close_pct")));
        List<Day> days = new ArrayList<>();
        List<BigDecimal> noTradeUniverse = new ArrayList<>(extraNoTrade), tradeUniverse = new ArrayList<>(), avoidAll = new ArrayList<>();
        int pickCount = 0;
        for (var entry : byDate.entrySet()) {
            List<Map<String, Object>> list = entry.getValue();
            String decision = (String) list.get(0).get("trade_decision");
            List<Map<String, Object>> picks = list.stream().filter(r -> "PICK".equals(r.get("kind"))).toList();
            List<BigDecimal> avoid = list.stream().filter(r -> "AVOID".equals(r.get("kind")))
                    .map(r -> decimal(r.get("excess_open_close_pct"))).toList();
            avoidAll.addAll(avoid);
            BigDecimal universe = decimal(list.get(0).get("universe_open_close_pct"));
            if ("NO_TRADE".equals(decision)) {
                noTradeUniverse.add(universe);
                continue;
            }
            tradeUniverse.add(universe);
            if (picks.isEmpty())
                continue;
            pickCount += picks.size();
            BigDecimal oc = mean(picks.stream().map(r -> decimal(r.get("open_close_pct"))).toList());
            BigDecimal net = mean(picks.stream().map(r -> decimal(r.get("net_open_close_pct"))).toList());
            days.add(new Day(entry.getKey(), decision, picks.size(), oc, net, universe,
                    oc == null || universe == null ? null : oc.subtract(universe), mean(avoid)));
        }
        List<Day> gate = days.subList(0, Math.min(GATE_DAYS, days.size()));
        BigDecimal gateMean = mean(gate.stream().map(Day::excess).toList());
        int gateWins = (int) gate.stream().filter(d -> d.excess() != null && d.excess().signum() > 0).count();
        String verdict = gate.size() < GATE_DAYS ? "PENDING"
                : gateMean != null && gateMean.signum() > 0 && gateWins >= GATE_WIN_DAYS ? "PASS" : "FAIL";
        return new Summary(days.size(), GATE_DAYS, GATE_WIN_DAYS, verdict, gateMean, gateWins,
                mean(days.stream().map(Day::pickOpenClose).toList()), mean(days.stream().map(Day::pickNet).toList()),
                mean(days.stream().map(Day::universeOpenClose).toList()), mean(days.stream().map(Day::excess).toList()),
                (int) days.stream().filter(d -> d.excess() != null && d.excess().signum() > 0).count(), pickCount,
                mean(avoidAll), avoidAll.size(), noTradeUniverse.size(), mean(noTradeUniverse), mean(tradeUniverse),
                List.copyOf(days));
    }

    /** 시가 매수 → 종가 매도의 비용 뺀 수익률(%)이에요. 매수 쪽은 수수료+슬리피지, 매도 쪽은 수수료+세금+슬리피지예요. */
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
        if (value == null)
            return null;
        return value instanceof BigDecimal big ? big : new BigDecimal(value.toString());
    }
}
