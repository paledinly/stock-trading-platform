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

    /** 기록 단계예요. PRELIMINARY는 장 마감 15분 뒤 1차, FINAL은 18:00 분봉 재보완·관측 재평가 뒤 최종이에요. */
    public enum Stage { PRELIMINARY, FINAL }

    /**
     * Realtime state sampled just before the feature freeze and after the entry deadline; nulls mean not observed.
     * 받는 것: 연결 유지 여부, 그 사이 버려진 메시지 증가량, 두 샘플 시각. UNOBSERVED는 관측 못 한 상태예요.
     */
    public record RealtimeWindow(Boolean connected, Long droppedDelta, Instant start, Instant end) {
        public static final RealtimeWindow UNOBSERVED = new RealtimeWindow(null, null, null, null);
        // connected가 있으면 관측된 것으로 봐요.
        boolean observed() { return connected != null; }
    }

    /**
     * 판정에 쓰는 하루치 사실이에요. 공식 run, 진입 마감, 선정·진입 수, 상태별 건수, 실시간 상태, 스캐너 탐지 수, 전략 버전을 담아요.
     * 후보 관측은 정밀(PRECISION, ADR-010 표본)과 Broad로 나눠 세요. 판정은 정밀만 보고, Broad는 실시간 구독·분봉 보완이
     * 없어 원래 결측이 많으므로 참고로만 남겨요.
     */
    public record Facts(Long runId, Instant runCompletedAt, Instant entryDeadline, int selected, int selectedEntered,
            Map<String, Integer> paperEntryStatuses, Map<String, Integer> precisionObservationStatuses,
            Map<String, Integer> broadObservationStatuses, RealtimeWindow realtime,
            Integer scannerDetections, String strategyVersion) {
        /** 정밀·Broad를 합친 상태별 건수예요(예전 observation_statuses와 같은 의미). */
        Map<String, Integer> observationStatuses() {
            Map<String, Integer> total = new TreeMap<>(precisionObservationStatuses);
            broadObservationStatuses.forEach((status, count) -> total.merge(status, count, Integer::sum));
            return total;
        }
    }

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
            Instant realtimeWindowEnd, Instant checkedAt, Map<String, Integer> precisionObservationStatuses,
            Map<String, Integer> broadObservationStatuses, Stage stage, Map<String, Object> indicators) {
        /**
         * 기록이 없는 거래일용 MISSING 행을 만들어요.
         * 받는 것: 날짜. 돌려주는 것: 이유 NO_HEALTH_RECORD인 DailyHealth.
         */
        static DailyHealth missing(LocalDate date) {
            return new DailyHealth(date, Verdict.MISSING, List.of("NO_HEALTH_RECORD"), false, null, null, null,
                    null, null, Map.of(), Map.of(), null, null, null, null, null, null, null, null, null);
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
        // 오늘이 측정일인 정밀 후보 관측 중 COMPLETED가 아닌 상태가 하나라도 있으면 관측 미완료로 봐요(Broad는 보지 않아요).
        if (facts.precisionObservationStatuses().entrySet().stream()
                .anyMatch(entry -> !"COMPLETED".equals(entry.getKey()) && entry.getValue() > 0))
            reasons.add("OBSERVATION_INCOMPLETE");
        return new Judgement(reasons.isEmpty() ? Verdict.COMPLETE : Verdict.DEGRADED, List.copyOf(reasons));
    }

    /**
     * Computes and upserts the current KST trade date only. Past dates are never rewritten.
     * 받는 것: 레코더가 찍은 실시간 구간(관측 못 했으면 오늘 이미 저장된 값을 재사용해요), 기록 단계(1차·최종).
     * 돌려주는 것: 저장한 오늘의 DailyHealth. 최종 기록은 같은 날 1차 기록을 덮어써요.
     */
    public synchronized DailyHealth recordToday(RealtimeWindow window, Stage stage) {
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
                realtime.connected(), realtime.droppedDelta(), realtime.start(), realtime.end(), now,
                facts.precisionObservationStatuses(), facts.broadObservationStatuses(), stage,
                indicators(today, facts.runId()));
        upsert(health);
        return health;
    }

    /**
     * 판정에 쓰지 않는 참고 지표예요. 판정은 정상인데 속은 잘못된 경우(2026-10-16~22 정밀 교체 멈춤, 전날 일봉 누락)를
     * 빨리 알아채려고 남겨요. 지표 하나를 못 세도 기록은 계속하고 그 값만 비워요.
     * - precisionReplacements: 그날 정밀 구독 교체로 빠진 종목 수(ALLOCATOR_REMOVED). 0이면 notes에 REPLACEMENTS_NONE.
     * - dailyDataFlagged: 그날 공식 평가에서 일봉 결함(DAILY_DATA_STALE·DAILY_DATA_MISSING)이 붙은 정밀 후보 수.
     * - coverageGaps, coverageStillMissing: 그날 장중 재시작·재연결 빈 구간 수와 채우지 못한 5분봉 수.
     */
    Map<String, Object> indicators(LocalDate today, Long runId) {
        Map<String, Object> values = new TreeMap<>();
        List<String> notes = new ArrayList<>();
        Integer replacements = quietly(() -> jdbc.queryForObject("select count(*) from precision_subscription_session "
                + "where session_date = ? and end_reason = 'ALLOCATOR_REMOVED'", Integer.class, today));
        values.put("precisionReplacements", replacements);
        if (replacements != null && replacements == 0 && runId != null)
            notes.add("REPLACEMENTS_NONE");
        Integer flagged = runId == null ? null : quietly(() -> dailyDataFlagged(runId));
        values.put("dailyDataFlagged", flagged);
        if (flagged != null && flagged > 0)
            notes.add("DAILY_DATA_FLAGGED");
        Map<String, Object> gaps = quietly(() -> jdbc.queryForMap("select count(*) gaps, coalesce(sum(still_missing), 0) "
                + "missing from realtime_coverage_gap where session_date = ?", today));
        values.put("coverageGaps", gaps == null ? null : ((Number) gaps.get("gaps")).intValue());
        values.put("coverageStillMissing", gaps == null ? null : ((Number) gaps.get("missing")).intValue());
        if (gaps != null && ((Number) gaps.get("missing")).intValue() > 0)
            notes.add("COVERAGE_GAP_UNFILLED");
        values.put("notes", notes);
        return values;
    }

    /** 공식 run의 평가 결과에서 일봉 결함이 붙은 정밀 후보 수를 세요. */
    private int dailyDataFlagged(long runId) {
        String snapshot = jdbc.queryForObject("select response_snapshot from closing_recommendation_run where id = ?",
                String.class, runId);
        if (snapshot == null)
            return 0;
        int count = 0;
        try {
            for (var evaluation : json.readTree(snapshot).path("evaluations")) {
                if (!"PRECISION".equals(evaluation.path("candidateSource").asText()))
                    continue;
                boolean flagged = List.of("DAILY_DATA_STALE", "DAILY_DATA_MISSING")
                        .contains(evaluation.path("decisionReason").asText());
                for (var missing : evaluation.path("missingFeatures"))
                    flagged |= missing.asText().startsWith("DAILY_DATA_");
                if (flagged)
                    count++;
            }
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unreadable run snapshot " + runId, error);
        }
        return count;
    }

    /** 참고 지표를 셀 때 실패하면(테이블 없음 등) null을 돌려줘요. 판정 기록을 막지 않기 위해서예요. */
    private static <T> T quietly(java.util.function.Supplier<T> query) {
        try {
            return query.get();
        } catch (RuntimeException ignored) {
            return null;
        }
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
        String observationSql = """
                select o.status, count(*) from closing_candidate_observation o
                  join closing_recommendation_run r on r.id = o.run_id
                 where o.expected_session_date = ? and r.execution_mode = 'FORWARD' and o.candidate_source %s 'PRECISION'
                 group by o.status""";
        Map<String, Integer> precision = counts(observationSql.formatted("="), today);
        Map<String, Integer> broad = counts(observationSql.formatted("<>"), today);
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
            return new Facts(null, null, deadline, 0, 0, Map.of(), precision, broad, realtime, detections, null);
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
                entered == null ? 0 : entered, entries, precision, broad, realtime, detections, run.strategyVersion());
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
                timestamp(row.checkedAt()), write(row.precisionObservationStatuses()),
                write(row.broadObservationStatuses()), row.stage() == null ? null : row.stage().name(),
                writeObject(row.indicators()), row.tradeDate() };
        int updated = jdbc.update("""
                update closing_daily_health set verdict=?, reasons=?, strategy_version=?, run_id=?, run_completed_at=?,
                    selected_count=?, selected_entered=?, paper_entry_statuses=?, observation_statuses=?,
                    realtime_connected=?, realtime_dropped_delta=?, realtime_window_start=?, realtime_window_end=?,
                    checked_at=?, precision_observation_statuses=?, broad_observation_statuses=?, record_stage=?,
                    indicators=?
                 where trade_date=?""", values);
        if (updated == 0)
            jdbc.update("""
                    insert into closing_daily_health(verdict, reasons, strategy_version, run_id, run_completed_at,
                        selected_count, selected_entered, paper_entry_statuses, observation_statuses,
                        realtime_connected, realtime_dropped_delta, realtime_window_start, realtime_window_end,
                        checked_at, precision_observation_statuses, broad_observation_statuses, record_stage, indicators,
                        trade_date) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", values);
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
                instant(rs, "realtime_window_start"), instant(rs, "realtime_window_end"), instant(rs, "checked_at"),
                nullableCounts(rs.getString("precision_observation_statuses")),
                nullableCounts(rs.getString("broad_observation_statuses")),
                rs.getString("record_stage") == null ? null : Stage.valueOf(rs.getString("record_stage")),
                readObject(rs.getString("indicators")));
    }

    private String writeObject(Map<String, Object> value) {
        if (value == null)
            return null;
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to serialize closing health indicators", error);
        }
    }

    private Map<String, Object> readObject(String value) {
        try {
            return value == null || value.isBlank() ? null : json.readValue(value, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException error) {
            return null;
        }
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

    /** V39 이전 행은 정밀·Broad 구분이 없어 null로 돌려줘요(화면은 합계 observationStatuses를 보여 줘요). */
    private Map<String, Integer> nullableCounts(String value) {
        return value == null ? null : parse(value);
    }

    /**
     * 상태별 건수를 이름순 JSON 문자열로 바꿔요.
     * 받는 것: 건수 Map. 돌려주는 것: JSON 문자열.
     */
    private String write(Map<String, Integer> value) {
        if (value == null)
            return null;
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
