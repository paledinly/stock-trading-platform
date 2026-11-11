/*
 * AI 실시간 감시의 DB 읽기·쓰기 파일이에요(V49 표 ai_watch_session, ai_signal_event, ai_virtual_position).
 * 그날 감시할 계획·종목, 과거 5분봉(intraday_research_bar)으로 만든 거래량 프로필, 전일 일봉도 여기서 읽어요.
 */
package com.sunmo.stockplatform.aiplan.realtime;

import com.sunmo.stockplatform.aiplan.realtime.AiWatchEngine.Exit;
import com.sunmo.stockplatform.aiplan.realtime.AiWatchEngine.PositionView;
import com.sunmo.stockplatform.aiplan.realtime.AiWatchEngine.SignalChanged;
import com.sunmo.stockplatform.aiplan.realtime.WatchRules.State;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

/** AI 감시 저장소예요. */
@Repository
public class AiWatchStore {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 그날 집계 대상 계획(장 시작 전 마지막 판, PREMARKET)이에요. */
    public record PlanRow(long id, String tradeDecision, String rawJson) { }

    /** 감시할 종목(PICK)이에요. */
    public record PickRow(long id, long stockId, String stockCode, String stockName, Integer priority, boolean topPriority) { }

    /** 감시 기록 한 날이에요. */
    public record SessionRow(LocalDate tradeDate, long planId, String tradeDecision, Instant startedAt, Instant endedAt,
            int pickCount, int subscribedCount, String unsubscribedCodes, BigDecimal positionAmount, String ruleVersion) { }

    /** 상태 변화 한 건이에요. indicators는 JSON 원문이에요. */
    public record EventRow(long id, String stockCode, String rule, String fromState, String toState, String reasons,
            String indicators, Instant sourceAt, Instant receivedAt, Instant evaluatedAt) { }

    private final JdbcTemplate jdbc;

    public AiWatchStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<PlanRow> plan(LocalDate date) {
        return jdbc.query("""
                select id, trade_decision, raw_json from ai_premarket_plan
                where target_date = ? and on_time and analysis_mode = 'PREMARKET'
                order by revision desc limit 1""",
                (rs, i) -> new PlanRow(rs.getLong("id"), rs.getString("trade_decision"), rs.getString("raw_json")),
                Date.valueOf(date)).stream().findFirst();
    }

    /** 최우선 감시 종목 먼저, 그다음 우선순위 순이에요. 이 순서대로 구독 자리를 받아요. */
    public List<PickRow> picks(long planId) {
        return jdbc.query("""
                select id, stock_id, stock_code, stock_name, priority, top_priority from ai_premarket_pick
                where plan_id = ? and kind = 'PICK'
                order by top_priority desc, case when priority is null then 1 else 0 end, priority, id""",
                (rs, i) -> new PickRow(rs.getLong("id"), rs.getLong("stock_id"), rs.getString("stock_code"),
                        rs.getString("stock_name"), (Integer) rs.getObject("priority"), rs.getBoolean("top_priority")),
                planId);
    }

    /**
     * 하는 일: 과거 거래일들의 5분봉으로 같은 칸 평균 거래량을 만들고, 전일 5분 평균 거래대금과 전일 고가·종가를 읽어요.
     * 받는 것: 종목 id, 과거 거래일 목록(가장 최근이 첫째, 보통 20일).
     * 칸이 비어 있는 날은 그 칸 거래량을 0으로 봐요(5분봉이 하나라도 있는 날만 일수로 세요).
     */
    public StockMonitor.Profile profile(long stockId, List<LocalDate> days, LocalDate today) {
        if (days.isEmpty()) return StockMonitor.Profile.empty();
        Map<LocalDate, Map<LocalTime, Long>> volumes = new HashMap<>();
        Map<LocalDate, BigDecimal> turnover = new HashMap<>();
        Map<LocalDate, Integer> bars = new HashMap<>();
        jdbc.query("""
                select start_time, volume, trading_value from intraday_research_bar
                where stock_id = ? and start_time >= ? and start_time < ?""", rs -> {
            var start = rs.getTimestamp("start_time").toInstant().atZone(ZONE);
            LocalDate day = start.toLocalDate();
            volumes.computeIfAbsent(day, d -> new HashMap<>()).merge(start.toLocalTime(), rs.getLong("volume"), Long::sum);
            turnover.merge(day, BigDecimal.valueOf(rs.getLong("trading_value")), BigDecimal::add);
            bars.merge(day, 1, Integer::sum);
        }, stockId, Timestamp.from(days.get(days.size() - 1).atStartOfDay(ZONE).toInstant()),
                Timestamp.from(today.atStartOfDay(ZONE).toInstant()));
        Set<LocalDate> wanted = new HashSet<>(days);
        List<LocalDate> used = volumes.keySet().stream().filter(wanted::contains).toList();
        Map<LocalTime, BigDecimal> slots = new HashMap<>();
        if (!used.isEmpty()) {
            Map<LocalTime, Long> sums = new HashMap<>();
            used.forEach(day -> volumes.get(day).forEach((slot, volume) -> sums.merge(slot, volume, Long::sum)));
            sums.forEach((slot, sum) -> slots.put(slot,
                    BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(used.size()), 4, RoundingMode.HALF_UP)));
        }
        LocalDate previous = days.get(0);
        BigDecimal previousBar = bars.containsKey(previous)
                ? turnover.get(previous).divide(BigDecimal.valueOf(bars.get(previous)), 0, RoundingMode.HALF_UP) : null;
        var daily = jdbc.query("""
                select high_price, close_price from daily_adjusted_candle
                where stock_id = ? and trade_date < ? order by trade_date desc limit 1""",
                (rs, i) -> new BigDecimal[] { rs.getBigDecimal("high_price"), rs.getBigDecimal("close_price") },
                stockId, Date.valueOf(today)).stream().findFirst().orElse(new BigDecimal[2]);
        return new StockMonitor.Profile(Map.copyOf(slots), used.size(), previousBar, daily[0], daily[1]);
    }

    /** 하는 일: 그날 감시 기록을 만들거나(재시작이면 시작 시각은 그대로 두고) 고쳐요. */
    public void session(LocalDate date, PlanRow plan, int pickCount, int subscribed, List<String> unsubscribed,
            BigDecimal amount, Instant now) {
        String missing = unsubscribed.isEmpty() ? null : String.join(",", unsubscribed);
        int updated = jdbc.update("""
                update ai_watch_session set plan_id = ?, trade_decision = ?, pick_count = ?, subscribed_count = ?,
                  unsubscribed_codes = ?, position_amount = ?, rule_version = ?, ended_at = null
                where trade_date = ?""", plan.id(), plan.tradeDecision(), pickCount, subscribed, missing, amount,
                WatchRules.RULE_VERSION, Date.valueOf(date));
        if (updated == 0)
            jdbc.update("""
                    insert into ai_watch_session(trade_date, plan_id, trade_decision, started_at, pick_count, subscribed_count,
                      unsubscribed_codes, position_amount, rule_version) values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    Date.valueOf(date), plan.id(), plan.tradeDecision(), Timestamp.from(now), pickCount, subscribed, missing,
                    amount, WatchRules.RULE_VERSION);
    }

    public void endSession(LocalDate date, Instant now) {
        jdbc.update("update ai_watch_session set ended_at = ? where trade_date = ?", Timestamp.from(now), Date.valueOf(date));
    }

    public Optional<SessionRow> session(LocalDate date) {
        return jdbc.query("select * from ai_watch_session where trade_date = ?", (rs, i) -> new SessionRow(
                rs.getDate("trade_date").toLocalDate(), rs.getLong("plan_id"), rs.getString("trade_decision"),
                instant(rs, "started_at"), instant(rs, "ended_at"), rs.getInt("pick_count"), rs.getInt("subscribed_count"),
                rs.getString("unsubscribed_codes"), rs.getBigDecimal("position_amount"), rs.getString("rule_version")),
                Date.valueOf(date)).stream().findFirst();
    }

    public void event(LocalDate date, SignalChanged change, String indicatorsJson) {
        jdbc.update("""
                insert into ai_signal_event(trade_date, pick_id, stock_code, rule, from_state, to_state, reasons, indicators,
                  source_at, received_at, evaluated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                Date.valueOf(date), change.pickId(), change.stockCode(), change.rule(),
                change.from() == null ? null : change.from().name(), change.to().name(), String.join(", ", change.reasons()),
                indicatorsJson, timestamp(change.sourceAt()), timestamp(change.receivedAt()), Timestamp.from(change.evaluatedAt()));
    }

    public List<EventRow> events(LocalDate date) {
        return jdbc.query("""
                select id, stock_code, rule, from_state, to_state, reasons, indicators, source_at, received_at, evaluated_at
                from ai_signal_event where trade_date = ? order by evaluated_at desc, id desc limit 1000""",
                (rs, i) -> new EventRow(rs.getLong("id"), rs.getString("stock_code"), rs.getString("rule"),
                        rs.getString("from_state"), rs.getString("to_state"), rs.getString("reasons"), rs.getString("indicators"),
                        instant(rs, "source_at"), instant(rs, "received_at"), instant(rs, "evaluated_at")),
                Date.valueOf(date));
    }

    /** 하는 일: 종목·규칙별 마지막 상태예요. 재시작 뒤 INVALIDATED·EXPIRED를 되살릴 때 써요. 키는 "종목코드|규칙"이에요. */
    public Map<String, State> lastStates(LocalDate date) {
        Map<String, State> states = new HashMap<>();
        jdbc.query("""
                select stock_code, rule, to_state from ai_signal_event where trade_date = ? order by evaluated_at, id""",
                rs -> { states.put(rs.getString("stock_code") + "|" + rs.getString("rule"), State.valueOf(rs.getString("to_state"))); },
                Date.valueOf(date));
        return states;
    }

    /** 하는 일: 가상 포지션을 저장해요. 같은 날·종목·규칙이 있으면 청산 값과 최고·최저가만 고쳐요. */
    public void position(LocalDate date, PositionView p, String costJson) {
        int updated = jdbc.update("""
                update ai_virtual_position set e1_exit_price = ?, e1_exit_at = ?, e1_reason = ?, e1_net_pct = ?,
                  e2_exit_price = ?, e2_exit_at = ?, e2_reason = ?, e2_net_pct = ?, max_price = ?, min_price = ?, tracking_gap = ?
                where trade_date = ? and pick_id = ? and rule = ?""",
                price(p.e1()), at(p.e1()), reason(p.e1()), net(p.e1()), price(p.e2()), at(p.e2()), reason(p.e2()), net(p.e2()),
                p.maxPrice(), p.minPrice(), p.trackingGap(), Date.valueOf(date), p.pickId(), p.rule());
        if (updated > 0) return;
        jdbc.update("""
                insert into ai_virtual_position(trade_date, pick_id, stock_code, rule, shadow, signal_at, entry_source_at,
                  entry_received_at, entry_tick_price, half_spread_pct, spread_source, entry_price, quantity, capacity_limited,
                  stop_price, e1_exit_price, e1_exit_at, e1_reason, e1_net_pct, e2_exit_price, e2_exit_at, e2_reason, e2_net_pct,
                  max_price, min_price, day_open_price, tracking_gap, cost_assumption)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                Date.valueOf(date), p.pickId(), p.stockCode(), p.rule(), p.shadow(), Timestamp.from(p.signalAt()),
                Timestamp.from(p.entrySourceAt()), Timestamp.from(p.entryReceivedAt()), p.entryTickPrice(), p.halfSpreadPct(),
                p.spreadSource(), p.entryPrice(), p.quantity(), p.capacityLimited(), p.stopPrice(), price(p.e1()), at(p.e1()),
                reason(p.e1()), net(p.e1()), price(p.e2()), at(p.e2()), reason(p.e2()), net(p.e2()), p.maxPrice(), p.minPrice(),
                p.dayOpenPrice(), p.trackingGap(), costJson);
    }

    public List<PositionView> positions(LocalDate date) {
        return jdbc.query("select * from ai_virtual_position where trade_date = ? order by signal_at, id", (rs, i) ->
                new PositionView(rs.getLong("pick_id"), rs.getString("stock_code"), rs.getString("rule"), rs.getBoolean("shadow"),
                        instant(rs, "signal_at"), instant(rs, "entry_source_at"), instant(rs, "entry_received_at"),
                        rs.getBigDecimal("entry_tick_price"), rs.getBigDecimal("half_spread_pct"), rs.getString("spread_source"),
                        rs.getBigDecimal("entry_price"), rs.getLong("quantity"), rs.getBoolean("capacity_limited"),
                        rs.getBigDecimal("stop_price"), exit(rs, "e1"), exit(rs, "e2"), rs.getBigDecimal("max_price"),
                        rs.getBigDecimal("min_price"), rs.getBigDecimal("day_open_price"), rs.getBoolean("tracking_gap")),
                Date.valueOf(date));
    }

    /**
     * 판정용 한 줄이에요: 가상 거래 하나와 같은 종목을 시가에 산 경우(1단계 채점의 비용 뺀 시가→종가)를 함께 읽어요.
     */
    public record TradeRow(LocalDate tradeDate, String stockCode, String rule, boolean shadow, BigDecimal e1NetPct,
            BigDecimal e2NetPct, BigDecimal openCloseNetPct) { }

    /** 하는 일: 청산이 끝난 가상 거래를 읽어요. 다른 규칙 버전으로 감시한 날은 빼요(버전마다 따로 집계). */
    public List<TradeRow> trades(String ruleVersion) {
        return jdbc.query("""
                select v.trade_date, v.stock_code, v.rule, v.shadow, v.e1_net_pct, v.e2_net_pct,
                  case when o.status = 'COMPLETE' then o.net_open_close_pct end as open_close_net
                from ai_virtual_position v left join ai_premarket_outcome o on o.pick_id = v.pick_id
                where v.e1_net_pct is not null and v.e2_net_pct is not null
                  and not exists (select 1 from ai_watch_session s where s.trade_date = v.trade_date and s.rule_version <> ?)
                order by v.trade_date, v.id""",
                (rs, i) -> new TradeRow(rs.getDate("trade_date").toLocalDate(), rs.getString("stock_code"), rs.getString("rule"),
                        rs.getBoolean("shadow"), rs.getBigDecimal("e1_net_pct"), rs.getBigDecimal("e2_net_pct"),
                        rs.getBigDecimal("open_close_net")), ruleVersion);
    }

    private static Exit exit(ResultSet rs, String prefix) throws SQLException {
        BigDecimal price = rs.getBigDecimal(prefix + "_exit_price");
        return price == null ? null : new Exit(price, instant(rs, prefix + "_exit_at"), rs.getString(prefix + "_reason"),
                rs.getBigDecimal(prefix + "_net_pct"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static BigDecimal price(Exit e) { return e == null ? null : e.price(); }
    private static Timestamp at(Exit e) { return e == null ? null : Timestamp.from(e.at()); }
    private static String reason(Exit e) { return e == null ? null : e.reason(); }
    private static BigDecimal net(Exit e) { return e == null ? null : e.netPct(); }
}
