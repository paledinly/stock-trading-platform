package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-trading-day completeness of the official closing pipeline (FORWARD run, paper entries,
 * candidate observations, realtime health around the freeze). Only today's row is ever written;
 * a trading day without a row means nothing was recorded (e.g. backend down) and reads as MISSING.
 */
@Service
public class ClosingDailyHealthService {
    /** Recorded after the close+10m observation run so its results are visible. */
    public static final Duration CHECK_DELAY = Duration.ofMinutes(15);
    static final int MAX_RANGE_DAYS = 366;
    static final int DEFAULT_RANGE_DAYS = 14;

    public enum Verdict { COMPLETE, DEGRADED, MISSING }

    /** Realtime state sampled just before the feature freeze and after the entry deadline; nulls mean not observed. */
    public record RealtimeWindow(Boolean connected, Long droppedDelta, Instant start, Instant end) {
        public static final RealtimeWindow UNOBSERVED = new RealtimeWindow(null, null, null, null);
        boolean observed() { return connected != null; }
    }

    public record Facts(Long runId, Instant runCompletedAt, Instant entryDeadline, int selected, int selectedEntered,
            Map<String, Integer> paperEntryStatuses, Map<String, Integer> observationStatuses, RealtimeWindow realtime) { }

    public record Judgement(Verdict verdict, List<String> reasons) { }

    /**
     * runCompletedAt is the evaluation completion time, realtimeWindowStart/End are host sample times and
     * checkedAt is when this record was computed; none of them is a source or paper-fill time.
     */
    public record DailyHealth(LocalDate tradeDate, Verdict verdict, List<String> reasons, boolean recorded,
            String strategyVersion, Long runId, Instant runCompletedAt, Integer selectedCount, Integer selectedEntered,
            Map<String, Integer> paperEntryStatuses, Map<String, Integer> observationStatuses,
            Boolean realtimeConnected, Long realtimeDroppedDelta, Instant realtimeWindowStart,
            Instant realtimeWindowEnd, Instant checkedAt) {
        static DailyHealth missing(LocalDate date) {
            return new DailyHealth(date, Verdict.MISSING, List.of("NO_HEALTH_RECORD"), false, null, null, null,
                    null, null, Map.of(), Map.of(), null, null, null, null, null);
        }
    }

    private static final TypeReference<Map<String, Integer>> COUNTS = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ClosingTradingCalendar calendar;
    private final ClosingRecommendationProperties policy;
    private final ObjectMapper json;

    public ClosingDailyHealthService(JdbcTemplate jdbc, ClosingTradingCalendar calendar,
            ClosingRecommendationProperties policy, ObjectMapper json) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.policy = policy;
        this.json = json;
    }

    /** Pure verdict. MISSING when no completed official run exists; otherwise every gap is a DEGRADED reason. */
    public static Judgement judge(Facts facts) {
        if (facts.runId() == null)
            return new Judgement(Verdict.MISSING, List.of("OFFICIAL_RUN_MISSING"));
        if (facts.runCompletedAt() == null)
            return new Judgement(Verdict.MISSING, List.of("OFFICIAL_RUN_INCOMPLETE"));
        List<String> reasons = new ArrayList<>();
        if (facts.entryDeadline() != null && !facts.runCompletedAt().isBefore(facts.entryDeadline()))
            reasons.add("EVALUATION_AFTER_ENTRY_DEADLINE");
        if (facts.selectedEntered() < facts.selected())
            reasons.add("SELECTED_ENTRY_MISSING");
        RealtimeWindow realtime = facts.realtime() == null ? RealtimeWindow.UNOBSERVED : facts.realtime();
        if (!realtime.observed())
            reasons.add("REALTIME_UNOBSERVED");
        else if (!realtime.connected())
            reasons.add("REALTIME_DISCONNECTED");
        if (realtime.droppedDelta() != null && realtime.droppedDelta() > 0)
            reasons.add("REALTIME_TICKS_DROPPED");
        if (facts.observationStatuses().entrySet().stream()
                .anyMatch(entry -> !"COMPLETED".equals(entry.getKey()) && entry.getValue() > 0))
            reasons.add("OBSERVATION_INCOMPLETE");
        return new Judgement(reasons.isEmpty() ? Verdict.COMPLETE : Verdict.DEGRADED, List.copyOf(reasons));
    }

    /** Computes and upserts the current KST trade date only. Past dates are never rewritten. */
    public synchronized DailyHealth recordToday(RealtimeWindow window) {
        Instant now = calendar.now();
        LocalDate today = now.atZone(ClosingTradingCalendar.ZONE).toLocalDate();
        RealtimeWindow realtime = window != null && window.observed() ? window : previousRealtime(today);
        Facts facts = facts(today, realtime);
        Judgement judgement = judge(facts);
        DailyHealth health = new DailyHealth(today, judgement.verdict(), judgement.reasons(), true,
                ClosingRecommendation.STRATEGY_VERSION, facts.runId(), facts.runCompletedAt(), facts.selected(),
                facts.selectedEntered(), facts.paperEntryStatuses(), facts.observationStatuses(),
                realtime.connected(), realtime.droppedDelta(), realtime.start(), realtime.end(), now);
        upsert(health);
        return health;
    }

    public List<DailyHealth> list(LocalDate from, LocalDate to) {
        Instant now = calendar.now();
        LocalDate today = now.atZone(ClosingTradingCalendar.ZONE).toLocalDate();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_RANGE_DAYS - 1L) : from;
        if (start.isAfter(end))
            throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST, "from must not be after to");
        if (ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS)
            throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                    "range must not exceed " + MAX_RANGE_DAYS + " days");
        Map<LocalDate, DailyHealth> recorded = new TreeMap<>();
        jdbc.query("select * from closing_daily_health where trade_date between ? and ?",
                rs -> { DailyHealth row = read(rs); recorded.put(row.tradeDate(), row); }, start, end);
        List<DailyHealth> result = new ArrayList<>();
        // Today before its check time and future dates have nothing to judge yet, so they are omitted.
        LocalDate last = end.isAfter(today) ? today : end;
        if (last.equals(today) && !recorded.containsKey(today)
                && now.isBefore(calendar.close(today).plus(CHECK_DELAY)))
            last = today.minusDays(1);
        for (LocalDate date = last; !date.isBefore(start); date = date.minusDays(1)) {
            DailyHealth row = recorded.get(date);
            if (row != null)
                result.add(row);
            else if (calendar.isTradingDay(date))
                result.add(DailyHealth.missing(date));
        }
        return List.copyOf(result);
    }

    private Facts facts(LocalDate today, RealtimeWindow realtime) {
        Instant deadline = today.atTime(policy.entryDeadline()).atZone(ClosingTradingCalendar.ZONE).toInstant();
        Map<String, Integer> observations = counts("""
                select o.status, count(*) from closing_candidate_observation o
                  join closing_recommendation_run r on r.id = o.run_id
                 where o.expected_session_date = ? and r.execution_mode = 'FORWARD'
                 group by o.status""", today);
        // The official request key wins; otherwise the latest FORWARD v9 run, as the scheduler resolves it.
        RowMapper<RunRow> runRow = (rs, index) -> new RunRow(rs.getLong(1), instant(rs, 2));
        List<RunRow> runs = jdbc.query("select id, completed_at from closing_recommendation_run where request_key = ?",
                runRow, "official-" + today + "-" + ClosingRecommendation.STRATEGY_VERSION);
        if (runs.isEmpty())
            runs = jdbc.query("""
                    select id, completed_at from closing_recommendation_run
                     where recommendation_date = ? and execution_mode = 'FORWARD' and strategy_version = ?
                     order by id desc""", runRow, today, ClosingRecommendation.STRATEGY_VERSION);
        if (runs.isEmpty())
            return new Facts(null, null, deadline, 0, 0, Map.of(), observations, realtime);
        RunRow run = runs.getFirst();
        long runId = run.id();
        Integer selected = jdbc.queryForObject("select count(*) from closing_recommendation where run_id = ?",
                Integer.class, runId);
        Integer entered = jdbc.queryForObject("""
                select count(*) from closing_paper_entry p
                  join stock s on s.stock_code = p.stock_code
                  join closing_recommendation r on r.stock_id = s.id and r.run_id = p.run_id
                 where p.run_id = ? and p.status = 'ENTERED'""", Integer.class, runId);
        Map<String, Integer> entries = counts(
                "select status, count(*) from closing_paper_entry where run_id = ? group by status", runId);
        return new Facts(runId, run.completedAt(), deadline, selected == null ? 0 : selected,
                entered == null ? 0 : entered, entries, observations, realtime);
    }

    private record RunRow(long id, Instant completedAt) { }

    /** A restart after the freeze window loses the in-memory samples; keep what was observed earlier today. */
    private RealtimeWindow previousRealtime(LocalDate today) {
        return jdbc.query("select realtime_connected, realtime_dropped_delta, realtime_window_start, realtime_window_end "
                        + "from closing_daily_health where trade_date = ?",
                (rs, index) -> new RealtimeWindow((Boolean) rs.getObject(1),
                        rs.getObject(2) == null ? null : rs.getLong(2), instant(rs, 3), instant(rs, 4)), today)
                .stream().filter(RealtimeWindow::observed).findFirst().orElse(RealtimeWindow.UNOBSERVED);
    }

    private void upsert(DailyHealth row) {
        Object[] values = {
                row.verdict().name(), String.join(",", row.reasons()), row.strategyVersion(), row.runId(),
                timestamp(row.runCompletedAt()), row.selectedCount(), row.selectedEntered(),
                write(row.paperEntryStatuses()), write(row.observationStatuses()), row.realtimeConnected(),
                row.realtimeDroppedDelta(), timestamp(row.realtimeWindowStart()), timestamp(row.realtimeWindowEnd()),
                timestamp(row.checkedAt()), row.tradeDate() };
        int updated = jdbc.update("""
                update closing_daily_health set verdict=?, reasons=?, strategy_version=?, run_id=?, run_completed_at=?,
                    selected_count=?, selected_entered=?, paper_entry_statuses=?, observation_statuses=?,
                    realtime_connected=?, realtime_dropped_delta=?, realtime_window_start=?, realtime_window_end=?,
                    checked_at=?
                 where trade_date=?""", values);
        if (updated == 0)
            jdbc.update("""
                    insert into closing_daily_health(verdict, reasons, strategy_version, run_id, run_completed_at,
                        selected_count, selected_entered, paper_entry_statuses, observation_statuses,
                        realtime_connected, realtime_dropped_delta, realtime_window_start, realtime_window_end,
                        checked_at, trade_date) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", values);
    }

    private DailyHealth read(ResultSet rs) throws SQLException {
        String reasons = rs.getString("reasons");
        return new DailyHealth(rs.getObject("trade_date", LocalDate.class), Verdict.valueOf(rs.getString("verdict")),
                reasons == null || reasons.isBlank() ? List.of() : Arrays.asList(reasons.split(",")), true,
                rs.getString("strategy_version"), rs.getObject("run_id") == null ? null : rs.getLong("run_id"),
                instant(rs, "run_completed_at"), rs.getInt("selected_count"), rs.getInt("selected_entered"),
                parse(rs.getString("paper_entry_statuses")), parse(rs.getString("observation_statuses")),
                (Boolean) rs.getObject("realtime_connected"),
                rs.getObject("realtime_dropped_delta") == null ? null : rs.getLong("realtime_dropped_delta"),
                instant(rs, "realtime_window_start"), instant(rs, "realtime_window_end"), instant(rs, "checked_at"));
    }

    private Map<String, Integer> counts(String sql, Object... args) {
        Map<String, Integer> result = new TreeMap<>();
        jdbc.query(sql, rs -> { result.put(rs.getString(1), rs.getInt(2)); }, args);
        return result;
    }

    private String write(Map<String, Integer> value) {
        try {
            return json.writeValueAsString(new TreeMap<>(value));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to serialize closing health counts", error);
        }
    }

    private Map<String, Integer> parse(String value) {
        try {
            return value == null || value.isBlank() ? Map.of() : json.readValue(value, COUNTS);
        } catch (JsonProcessingException error) {
            return Map.of();
        }
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
