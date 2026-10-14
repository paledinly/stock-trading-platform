/*
 * 거래일마다 공식 마감 파이프라인이 빠짐없이 돌았는지(공식 run, 가상 진입, 후보 관측, 실시간 연결)를 판정해 기록하고 조회해요.
 * 판정은 COMPLETE(정상), DEGRADED(일부 결함), MISSING(기록 없음 또는 공식 run 없음) 세 가지예요.
 * ClosingDailyHealthRecorder가 하루 한 번 기록하고, ClosingDailyHealthController가 기간 조회에 써요.
 */
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
 * 하는 일: 그날의 사실(Facts)을 DB에서 모아 판정하고 오늘 행만 저장(있으면 갱신)해요.
 * 매일 저녁 "오늘 문 열었나, 손님 받았나, 장부 정리했나"를 체크리스트로 확인하고 일지에 적는 당직자 같아요.
 * 일지가 없는 영업일은 "확인 못 함(MISSING)"으로 보여 줘요.
 */
@Service
public class ClosingDailyHealthService {
    /**
     * Recorded after the close+10m observation run so its results are visible.
     * 장 마감(15:30) 뒤 15분을 기다렸다가 기록해요. 마감+10분 관측 실행의 결과를 반영하기 위해서예요.
     */
    public static final Duration CHECK_DELAY = Duration.ofMinutes(15);
    // 조회 기간 상한(366일)과 기본 조회 기간(14일)이에요.
    static final int MAX_RANGE_DAYS = 366;
    static final int DEFAULT_RANGE_DAYS = 14;

    /** 하루 판정 결과예요. COMPLETE는 정상, DEGRADED는 일부 결함, MISSING은 공식 run이나 기록이 없음을 뜻해요. */
    public enum Verdict { COMPLETE, DEGRADED, MISSING }

    /**
     * Realtime state sampled just before the feature freeze and after the entry deadline; nulls mean not observed.
     * 받는 것: 연결 유지 여부, 그 사이 버려진 메시지 증가량, 두 샘플 시각. UNOBSERVED는 관측 못 한 상태예요.
     */
    public record RealtimeWindow(Boolean connected, Long droppedDelta, Instant start, Instant end) {
        public static final RealtimeWindow UNOBSERVED = new RealtimeWindow(null, null, null, null);
        // connected가 있으면 관측된 것으로 봐요.
        boolean observed() { return connected != null; }
    }

    /** 판정에 쓰는 하루치 사실이에요. 공식 run, 진입 마감, 선정·진입 수, 상태별 건수, 실시간 상태, 스캐너 탐지 수, 전략 버전을 담아요. */
    public record Facts(Long runId, Instant runCompletedAt, Instant entryDeadline, int selected, int selectedEntered,
            Map<String, Integer> paperEntryStatuses, Map<String, Integer> observationStatuses, RealtimeWindow realtime,
            Integer scannerDetections, String strategyVersion) { }

    /** 판정 결과와 이유 목록이에요. */
    public record Judgement(Verdict verdict, List<String> reasons) { }

    /**
     * runCompletedAt is the evaluation completion time, realtimeWindowStart/End are host sample times and
     * checkedAt is when this record was computed; none of them is a source or paper-fill time.
     * 저장·응답용 하루치 점검 행이에요. recorded=false면 DB에 기록이 없는 날을 뜻해요.
     */
    public record DailyHealth(LocalDate tradeDate, Verdict verdict, List<String> reasons, boolean recorded,
            String strategyVersion, Long runId, Instant runCompletedAt, Integer selectedCount, Integer selectedEntered,
            Map<String, Integer> paperEntryStatuses, Map<String, Integer> observationStatuses,
            Boolean realtimeConnected, Long realtimeDroppedDelta, Instant realtimeWindowStart,
            Instant realtimeWindowEnd, Instant checkedAt) {
        /**
         * 기록이 없는 거래일용 MISSING 행을 만들어요.
         * 받는 것: 날짜. 돌려주는 것: 이유 NO_HEALTH_RECORD인 DailyHealth.
         */
        static DailyHealth missing(LocalDate date) {
            return new DailyHealth(date, Verdict.MISSING, List.of("NO_HEALTH_RECORD"), false, null, null, null,
                    null, null, Map.of(), Map.of(), null, null, null, null, null);
        }
    }

    // 상태별 건수 JSON을 Map으로 읽기 위한 타입 정보예요.
    private static final TypeReference<Map<String, Integer>> COUNTS = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ClosingTradingCalendar calendar;
    private final ClosingRecommendationProperties policy;
    private final ObjectMapper json;

    /**
     * DB 접근, 거래일 달력, 마감 정책, JSON 변환기를 받아요.
     */
    public ClosingDailyHealthService(JdbcTemplate jdbc, ClosingTradingCalendar calendar,
            ClosingRecommendationProperties policy, ObjectMapper json) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.policy = policy;
        this.json = json;
    }

    /**
     * Pure verdict. MISSING when no completed official run exists; otherwise every gap is a DEGRADED reason.
     * 받는 것: 하루치 사실. 돌려주는 것: 판정과 이유 목록. DB를 건드리지 않는 순수 계산이에요.
     */
    public static Judgement judge(Facts facts) {
        if (facts.runId() == null)
            return new Judgement(Verdict.MISSING, List.of("OFFICIAL_RUN_MISSING"));
        if (facts.runCompletedAt() == null)
            return new Judgement(Verdict.MISSING, List.of("OFFICIAL_RUN_INCOMPLETE"));
        // 처리: 진입 마감 뒤 평가 완료, 선정 종목 진입 누락, 실시간 미관측·끊김·메시지 버림을 이유로 모아요.
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
        // No scanner detection between the evaluation start and the freeze means no precision candidate could exist.
        if (facts.scannerDetections() != null && facts.scannerDetections() == 0)
            reasons.add("SCANNER_DETECTIONS_MISSING");
        // 오늘이 측정일인 후보 관측 중 COMPLETED가 아닌 상태가 하나라도 있으면 관측 미완료로 봐요.
        if (facts.observationStatuses().entrySet().stream()
                .anyMatch(entry -> !"COMPLETED".equals(entry.getKey()) && entry.getValue() > 0))
            reasons.add("OBSERVATION_INCOMPLETE");
        return new Judgement(reasons.isEmpty() ? Verdict.COMPLETE : Verdict.DEGRADED, List.copyOf(reasons));
    }

    /**
     * Computes and upserts the current KST trade date only. Past dates are never rewritten.
     * 받는 것: 레코더가 찍은 실시간 구간(관측 못 했으면 오늘 이미 저장된 값을 재사용해요).
     * 돌려주는 것: 저장한 오늘의 DailyHealth.
     */
    public synchronized DailyHealth recordToday(RealtimeWindow window) {
        // 준비: 오늘 날짜와 실시간 구간을 정하고, DB에서 사실을 모아 판정해요.
        Instant now = calendar.now();
        LocalDate today = now.atZone(ClosingTradingCalendar.ZONE).toLocalDate();
        RealtimeWindow realtime = window != null && window.observed() ? window : previousRealtime(today);
        Facts facts = facts(today, realtime);
        Judgement judgement = judge(facts);
        // 결과: 판정을 오늘 행으로 만들어 저장(갱신 또는 추가)하고 돌려줘요.
        DailyHealth health = new DailyHealth(today, judgement.verdict(), judgement.reasons(), true,
                facts.strategyVersion() == null ? ClosingRecommendation.STRATEGY_VERSION : facts.strategyVersion(),
                facts.runId(), facts.runCompletedAt(), facts.selected(),
                facts.selectedEntered(), facts.paperEntryStatuses(), facts.observationStatuses(),
                realtime.connected(), realtime.droppedDelta(), realtime.start(), realtime.end(), now);
        upsert(health);
        return health;
    }

    /**
     * 기간 안의 날짜별 점검 행을 최신 날짜부터 돌려줘요.
     * 받는 것: 시작일·종료일(없으면 오늘까지 최근 14일). 돌려주는 것: 기록된 행, 기록 없는 거래일은 MISSING 행.
     * 시작일이 종료일보다 늦거나 기간이 366일 이상이면 400 오류예요.
     */
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
        // 처리: 저장된 행을 날짜별로 읽어 두고, 거래일인데 행이 없는 날은 MISSING으로 채워요.
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

    /**
     * 오늘 판정에 필요한 사실을 DB에서 모아요.
     * 받는 것: 오늘 날짜, 실시간 구간. 돌려주는 것: Facts(공식 run이 없으면 runId가 null).
     */
    private Facts facts(LocalDate today, RealtimeWindow realtime) {
        Instant deadline = today.atTime(policy.entryDeadline()).atZone(ClosingTradingCalendar.ZONE).toInstant();
        // 평가 시작~입력 마감 사이 스캐너 탐지 수, 그리고 오늘이 측정일인 FORWARD 관측의 상태별 건수를 세요.
        Integer detections = jdbc.queryForObject("""
                select count(*) from scanner_detection
                 where market_session_date = ? and detected_at >= ? and detected_at <= ?""", Integer.class, today,
                Timestamp.from(today.atTime(policy.evaluationStart()).atZone(ClosingTradingCalendar.ZONE).toInstant()),
                Timestamp.from(today.atTime(policy.featureFreezeAt()).atZone(ClosingTradingCalendar.ZONE).toInstant()));
        Map<String, Integer> observations = counts("""
                select o.status, count(*) from closing_candidate_observation o
                  join closing_recommendation_run r on r.id = o.run_id
                 where o.expected_session_date = ? and r.execution_mode = 'FORWARD'
                 group by o.status""", today);
        // The current version's official key wins. A restart after a version change must still find the run
        // that actually executed today, so the fallback is the latest official or FORWARD run of any version.
        RowMapper<RunRow> runRow = (rs, index) -> new RunRow(rs.getLong(1), instant(rs, 2), rs.getString(3));
        List<RunRow> runs = jdbc.query(
                "select id, completed_at, strategy_version from closing_recommendation_run where request_key = ?",
                runRow, "official-" + today + "-" + ClosingRecommendation.STRATEGY_VERSION);
        if (runs.isEmpty())
            runs = jdbc.query("""
                    select id, completed_at, strategy_version from closing_recommendation_run
                     where recommendation_date = ? and execution_mode = 'FORWARD'
                     order by case when request_key like ? then 0 else 1 end, id desc""",
                    runRow, today, "official-" + today + "-%");
        if (runs.isEmpty())
            return new Facts(null, null, deadline, 0, 0, Map.of(), observations, realtime, detections, null);
        // 공식 run이 있으면 선정 수, 선정 종목 중 가상 진입 성공 수, 가상 진입 상태별 건수를 세요.
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
                entered == null ? 0 : entered, entries, observations, realtime, detections, run.strategyVersion());
    }

    /** 공식 run 조회 결과 한 줄(ID, 완료 시각, 전략 버전)이에요. */
    private record RunRow(long id, Instant completedAt, String strategyVersion) { }

    /**
     * A restart after the freeze window loses the in-memory samples; keep what was observed earlier today.
     * 받는 것: 오늘 날짜. 돌려주는 것: 오늘 행에 저장돼 있던 관측된 실시간 구간, 없으면 UNOBSERVED.
     */
    private RealtimeWindow previousRealtime(LocalDate today) {
        return jdbc.query("select realtime_connected, realtime_dropped_delta, realtime_window_start, realtime_window_end "
                        + "from closing_daily_health where trade_date = ?",
                (rs, index) -> new RealtimeWindow((Boolean) rs.getObject(1),
                        rs.getObject(2) == null ? null : rs.getLong(2), instant(rs, 3), instant(rs, 4)), today)
                .stream().filter(RealtimeWindow::observed).findFirst().orElse(RealtimeWindow.UNOBSERVED);
    }

    /**
     * 오늘 행을 갱신하고, 갱신된 행이 없으면 새로 넣어요.
     * 받는 것: 저장할 DailyHealth. 돌려주는 것: 없음.
     */
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

    /**
     * DB 한 줄을 DailyHealth로 읽어요.
     * 받는 것: 결과 행. 돌려주는 것: recorded=true인 DailyHealth.
     */
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

    /**
     * "상태, 건수" 두 열을 돌려주는 SQL을 실행해 Map으로 모아요.
     * 받는 것: SQL과 인자. 돌려주는 것: 상태 이름순 Map.
     */
    private Map<String, Integer> counts(String sql, Object... args) {
        Map<String, Integer> result = new TreeMap<>();
        jdbc.query(sql, rs -> { result.put(rs.getString(1), rs.getInt(2)); }, args);
        return result;
    }

    /**
     * 상태별 건수를 이름순 JSON 문자열로 바꿔요.
     * 받는 것: 건수 Map. 돌려주는 것: JSON 문자열.
     */
    private String write(Map<String, Integer> value) {
        try {
            return json.writeValueAsString(new TreeMap<>(value));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to serialize closing health counts", error);
        }
    }

    /**
     * 저장된 JSON 문자열을 상태별 건수 Map으로 읽어요.
     * 받는 것: JSON 문자열. 돌려주는 것: Map, 비었거나 깨졌으면 빈 Map.
     */
    private Map<String, Integer> parse(String value) {
        try {
            return value == null || value.isBlank() ? Map.of() : json.readValue(value, COUNTS);
        } catch (JsonProcessingException error) {
            return Map.of();
        }
    }

    // 시각 변환 도우미들: Instant ↔ DB Timestamp. null은 null로 둬요.
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
