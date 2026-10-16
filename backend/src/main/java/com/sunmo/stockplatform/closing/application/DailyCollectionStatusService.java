/*
 * 하루 동안 자동으로 모아야 하는 장마감 자료가 실제로 저장됐는지 DB에서 세어 보여 주는 조회 전용 서비스예요.
 * 웹의 '오늘'(운영 현황) 화면이 쓰며, 아무것도 저장하거나 고치지 않아요. 판정(COMPLETE/DEGRADED)은 ClosingDailyHealthService가 맡아요.
 * ClosingDailyHealthController의 GET /api/v1/closing-recommendations/daily-collection이 호출해요.
 */
package com.sunmo.stockplatform.closing.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 한 거래일의 자동 수집 결과(공식 run, 가상 진입, 상한가 감시, 후보 맥락, 15:25 종가 봉, 전일 후보 관측과 분봉 보완, DART 공시,
 * 18:30 수정주가 일봉, 08:10 후보 일봉 준비와 14:25·14:35 정밀 후보 일봉 보완의 저장 흔적)를
 * 개수와 마지막 시각으로 모아 돌려줘요. 시각은 모두 호스트 수신·저장 시각이며 원천(체결) 시각이 아니에요.
 * Read-only counts for the operations page; absence of rows is reported as zero counts, never inferred as success.
 */
@Service
public class DailyCollectionStatusService {
    private final JdbcTemplate jdbc;
    private final ClosingTradingCalendar calendar;

    /** 받는 것: DB 접근 도구, 거래일 달력. 돌려주는 것: 서비스 객체. */
    public DailyCollectionStatusService(JdbcTemplate jdbc, ClosingTradingCalendar calendar) {
        this.jdbc = jdbc;
        this.calendar = calendar;
    }

    /** 공식 FORWARD 평가 run 요약이에요. completedAt은 평가 완료시각이에요. */
    public record Run(long runId, String strategyVersion, Instant completedAt) { }

    /** 상한가 감시 표본 하나(15:16 또는 15:18)의 출처별 행 수예요. rankingRows는 순위 응답 건수 기록이 있는 행 수예요. */
    public record LimitUpSample(String sampleLabel, int rows, int errors, int rankingRows, Map<String, Integer> sources,
            Instant firstReceivedAt) { }

    /** 행 수, 오류 행 수, 마지막 저장(수신) 시각이에요. */
    public record Counted(int rows, int errors, Instant lastAt) { }

    /** 15:25 종가 봉: 저장된 종목 수와 그중 거래량이 있는 종목 수예요. */
    public record ClosingBar(int stocks, int withVolume) { }

    /** 이 날을 관측 세션으로 갖는 전일 후보 관측의 상태별 수와, 이 날 분봉 보완(BACKFILL)으로 저장된 5분봉 수예요. */
    public record Observation(Map<String, Integer> statuses, Instant lastEvaluatedAt, int backfilledBars) { }

    /**
     * 그 시간대(한국시간)에 저장된 일봉 수예요. 일봉 준비는 빠진 일봉만 새로 저장하므로, 아침 준비(08:10)는 보통 전 거래일 일봉을
     * 종목마다 하나 이상 남겨요. 실행 기록 테이블이 없어서 이 저장 흔적으로 실행 여부를 판단해요(0이면 미실행 또는 저장할 것 없음).
     */
    public record DailyCandles(int rows, int stocks, Instant lastAt) { }

    /** 화면에 그대로 내려주는 하루 수집 현황이에요. tradingDay가 false면 나머지는 비어 있어요. */
    public record DailyCollection(LocalDate date, boolean tradingDay, LocalDate previousTradingDay, Instant checkedAt,
            Run run, Map<String, Integer> paperEntries, List<LimitUpSample> limitUp, Counted candidateContext,
            ClosingBar closingBar, Observation observation, Counted dart, Counted adjustedDaily,
            DailyCandles morningDaily, DailyCandles precisionDaily) { }

    /**
     * 하는 일: 날짜(없으면 오늘, 한국시간)의 수집 현황을 세어요.
     * 받는 것: 날짜 또는 null. 돌려주는 것: 하루 수집 현황.
     */
    public DailyCollection status(LocalDate requested) {
        LocalDate date = requested == null ? calendar.today() : requested;
        Instant checkedAt = calendar.now();
        if (!calendar.isTradingDay(date))
            return new DailyCollection(date, false, calendar.previousTradingDay(date), checkedAt, null, Map.of(),
                    List.of(), new Counted(0, 0, null), new ClosingBar(0, 0), new Observation(Map.of(), null, 0),
                    dart(date), adjustedDaily(date), new DailyCandles(0, 0, null), new DailyCandles(0, 0, null));
        Run run = run(date);
        Instant dayStart = date.atStartOfDay(ClosingTradingCalendar.ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(ClosingTradingCalendar.ZONE).toInstant();
        return new DailyCollection(date, true, calendar.previousTradingDay(date), checkedAt, run,
                run == null ? Map.of() : counts("select status, count(*) from closing_paper_entry where run_id = ? "
                        + "group by status", run.runId()),
                limitUp(date),
                run == null ? new Counted(0, 0, null) : counted("select count(*), "
                        + "sum(case when error is not null then 1 else 0 end), max(received_at) "
                        + "from closing_candidate_context where run_id = ?", run.runId()),
                closingBar(date),
                new Observation(counts("select status, count(*) from closing_candidate_observation "
                        + "where expected_session_date = ? group by status", date),
                        instant(jdbc.queryForObject("select max(evaluated_at) from closing_candidate_observation "
                                + "where expected_session_date = ?", Timestamp.class, date)),
                        integer(jdbc.queryForObject("select count(*) from stock_candle where timeframe = '5M' "
                                + "and source = 'BACKFILL' and start_time >= ? and start_time < ?", Long.class,
                                Timestamp.from(dayStart), Timestamp.from(dayEnd)))),
                dart(date), adjustedDaily(date),
                dailyCandles(date, LocalTime.of(7, 0), LocalTime.of(9, 0)),
                dailyCandles(date, LocalTime.of(14, 20), LocalTime.of(14, 50)));
    }

    /** 그날의 가장 최근 공식 FORWARD run을 찾아요. 없으면 null이에요. */
    private Run run(LocalDate date) {
        List<Run> rows = jdbc.query("select id, strategy_version, completed_at from closing_recommendation_run "
                + "where recommendation_date = ? and execution_mode = 'FORWARD' order by id desc",
                (rs, i) -> new Run(rs.getLong(1), rs.getString(2), instant(rs.getTimestamp(3))), date);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 상한가 감시를 표본(15:16·15:18)별, 출처별로 세요. 출처 기록이 없던 예전 행은 UNKNOWN이에요. */
    private List<LimitUpSample> limitUp(LocalDate date) {
        Map<String, LimitUpSample> samples = new LinkedHashMap<>();
        jdbc.query("select sample_label, coalesce(candidate_source, 'UNKNOWN'), count(*), "
                + "sum(case when error is not null then 1 else 0 end), "
                + "sum(case when ranking_rows is not null then 1 else 0 end), min(ranking_received_at) "
                + "from limit_up_watch where session_date = ? group by sample_label, coalesce(candidate_source, 'UNKNOWN') "
                + "order by sample_label", rs -> {
                    String label = rs.getString(1);
                    LimitUpSample previous = samples.get(label);
                    Map<String, Integer> sources = new LinkedHashMap<>(previous == null ? Map.of() : previous.sources());
                    sources.put(rs.getString(2), rs.getInt(3));
                    Instant first = instant(rs.getTimestamp(6));
                    if (previous != null && previous.firstReceivedAt() != null
                            && (first == null || previous.firstReceivedAt().isBefore(first)))
                        first = previous.firstReceivedAt();
                    samples.put(label, new LimitUpSample(label,
                            (previous == null ? 0 : previous.rows()) + rs.getInt(3),
                            (previous == null ? 0 : previous.errors()) + rs.getInt(4),
                            (previous == null ? 0 : previous.rankingRows()) + rs.getInt(5),
                            Map.copyOf(sources), first));
                }, date);
        return new ArrayList<>(samples.values());
    }

    /** 15:25에 시작하는 5분봉(종가 단일가 체결이 합쳐지는 봉)의 종목 수와 거래량 있는 종목 수예요. */
    private ClosingBar closingBar(LocalDate date) {
        Timestamp start = Timestamp.from(date.atTime(15, 25).atZone(ClosingTradingCalendar.ZONE).toInstant());
        return jdbc.queryForObject("select count(*), sum(case when volume > 0 then 1 else 0 end) from stock_candle "
                + "where timeframe = '5M' and start_time = ?", (rs, i) -> new ClosingBar(rs.getInt(1), rs.getInt(2)), start);
    }

    /** 그날 접수된 DART 공시 수와 마지막 수집 시각이에요. 18:10 수집과 다음 날 07:40 보충이 채워요. */
    private Counted dart(LocalDate date) {
        return counted("select count(*), 0, max(fetched_at) from dart_filing where rcept_dt = ?", date);
    }

    /**
     * 그날(한국시간) 수정주가 일봉 수집이 확인한 종목 수와 마지막 확인 시각이에요. 상태 API는 메모리 값이라 재시작하면 지워지므로
     * DB의 종목별 진행 기록(daily_adjusted_sweep_state.swept_at)으로 세요.
     */
    private Counted adjustedDaily(LocalDate date) {
        Timestamp start = Timestamp.from(date.atStartOfDay(ClosingTradingCalendar.ZONE).toInstant());
        Timestamp end = Timestamp.from(date.plusDays(1).atStartOfDay(ClosingTradingCalendar.ZONE).toInstant());
        return jdbc.queryForObject("select count(*), 0, max(swept_at) from daily_adjusted_sweep_state "
                + "where swept_at >= ? and swept_at < ?", (rs, i) -> new Counted(rs.getInt(1), rs.getInt(2),
                instant(rs.getTimestamp(3))), start, end);
    }

    /**
     * 그날 from~to(한국시간)에 저장·갱신된 일봉(1D) 수와 종목 수, 마지막 시각이에요.
     * 07:00~09:00은 08:10 후보 일봉 준비(아침 수동 실행 포함), 14:20~14:50은 14:25·14:35 정밀 후보 일봉 보완을 봐요.
     */
    private DailyCandles dailyCandles(LocalDate date, LocalTime from, LocalTime to) {
        Timestamp start = Timestamp.from(date.atTime(from).atZone(ClosingTradingCalendar.ZONE).toInstant());
        Timestamp end = Timestamp.from(date.atTime(to).atZone(ClosingTradingCalendar.ZONE).toInstant());
        return jdbc.queryForObject("select count(*), count(distinct stock_id), max(updated_at) from stock_candle "
                + "where timeframe = '1D' and updated_at >= ? and updated_at < ?",
                (rs, i) -> new DailyCandles(rs.getInt(1), rs.getInt(2), instant(rs.getTimestamp(3))), start, end);
    }

    /** "상태, 개수" 두 열 결과를 순서 있는 맵으로 바꿔요. */
    private Map<String, Integer> counts(String sql, Object arg) {
        Map<String, Integer> result = new LinkedHashMap<>();
        jdbc.query(sql, rs -> { result.put(rs.getString(1), rs.getInt(2)); }, arg);
        return result;
    }

    /** "개수, 오류 수, 마지막 시각" 한 줄 결과를 읽어요. */
    private Counted counted(String sql, Object arg) {
        return jdbc.queryForObject(sql, (rs, i) -> new Counted(rs.getInt(1), rs.getInt(2), instant(rs.getTimestamp(3))), arg);
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static int integer(Long value) { return value == null ? 0 : Math.toIntExact(value); }
}
