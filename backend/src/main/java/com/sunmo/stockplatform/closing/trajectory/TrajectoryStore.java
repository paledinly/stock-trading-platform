/*
 * Trajectory 연구 데이터(1분 집계, 맥락 관측, 스냅샷, 오전 결과)를 DB 표에 JSON 문자열로 저장·조회하는 저장소예요.
 * JPA(자바 객체와 DB 표를 이어 주는 기술) 대신 JdbcTemplate로 SQL을 직접 실행해요. 1분 집계·맥락·스냅샷은 한 번 넣으면 덮어쓰지 않아요.
 * TrajectoryService, TrajectoryController, MorningOutcomeService 등 Trajectory 모듈이 사용해요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.*;

/**
 * closing_minute_feature, closing_context_observation, closing_trajectory_snapshot 표를 다루는 레포지토리(DB 창구)예요.
 * 볼펜으로 쓰는 일지처럼 같은 키(종목+시각)의 기록이 이미 있으면 새로 쓰지 않고 그대로 둬요(ON CONFLICT DO NOTHING).
 * 단, 스냅샷의 outcome(오전 결과) 칸만은 나중에 채워 넣어요.
 */
@Repository
public class TrajectoryStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    /** 하는 일: DB 실행기와 JSON 변환기를 받아 보관해요. 받는 것: JdbcTemplate, ObjectMapper. 돌려주는 것: 저장소 객체예요. */
    public TrajectoryStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    /** 하는 일: 1분 집계 한 줄을 저장해요(이미 있으면 무시). 받는 것: Minute. 돌려주는 것: 없어요. */
    public void minute(Minute row) {
        jdbc.update("INSERT INTO closing_minute_feature VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                row.symbol(), Timestamp.from(row.start()), Timestamp.from(row.finalizedAt()), json(row));
    }
    /** 하는 일: 맥락 관측 한 줄을 저장해요(같은 종류·종목·범위·수신시각이면 무시). 받는 것: Context. 돌려주는 것: 없어요. */
    public void context(Context row) {
        jdbc.update("INSERT INTO closing_context_observation VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                row.kind(), row.symbol(), row.scope(), Timestamp.from(row.receivedAt()), json(row));
    }
    /**
     * 하는 일: 기간 안에 1분 집계가 있는 종목을 가장 최근 집계 순으로 골라요.
     * 받는 것: 시작(포함), 끝(제외), 최대 개수예요. 돌려주는 것: 종목 코드 목록이에요.
     */
    public List<String> recentSymbols(Instant since, Instant until, int limit) {
        return jdbc.query("SELECT symbol FROM closing_minute_feature WHERE start_time>=? AND start_time<? GROUP BY symbol ORDER BY max(start_time) DESC,symbol LIMIT ?",
                (rs, n) -> rs.getString(1), Timestamp.from(since), Timestamp.from(until), limit);
    }
    /** 하는 일: 스냅샷을 저장해요(같은 종목·기준시각이 있으면 무시). 받는 것: Snapshot. 돌려주는 것: 새로 저장됐으면 true예요. */
    public boolean snapshot(Snapshot row) {
        return jdbc.update("INSERT INTO closing_trajectory_snapshot(symbol,evaluation_time,evaluated_at,payload) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                row.symbol(), Timestamp.from(row.timestamp()), Timestamp.from(row.evaluatedAt()), json(row)) == 1;
    }
    /** 하는 일: 한 종목의 1분 집계를 시간순으로 읽어요. 받는 것: 종목, 시작(포함), 끝(제외). 돌려주는 것: Minute 목록이에요. */
    public List<Minute> minutes(String symbol, Instant from, Instant until) {
        return jdbc.query("SELECT payload FROM closing_minute_feature WHERE symbol=? AND start_time>=? AND start_time<? ORDER BY start_time",
                (rs, n) -> read(rs.getString(1), Minute.class), symbol, Timestamp.from(from), Timestamp.from(until));
    }
    /**
     * 하는 일: 종목 자신과 소속 시장(지수)의 맥락 관측을 수신시각 순으로 읽어요.
     * 받는 것: 종목, 시장 이름, 시작(포함), 끝(포함)이에요. 돌려주는 것: Context 목록이에요.
     */
    public List<Context> contexts(String symbol, String market, Instant from, Instant until) {
        return jdbc.query("SELECT payload FROM closing_context_observation WHERE symbol IN (?,?) AND received_at>=? AND received_at<=? ORDER BY received_at",
                (rs, n) -> read(rs.getString(1), Context.class), symbol, market, Timestamp.from(from), Timestamp.from(until));
    }
    /** 하는 일: 기간 안 모든 스냅샷을 기준시각·종목 순으로 읽어요. 받는 것: 시작(포함), 끝(제외). 돌려주는 것: Snapshot 목록이에요. */
    public List<Snapshot> snapshots(Instant from, Instant until) {
        return jdbc.query("SELECT payload FROM closing_trajectory_snapshot WHERE evaluation_time>=? AND evaluation_time<? ORDER BY evaluation_time,symbol",
                (rs, n) -> read(rs.getString(1), Snapshot.class), Timestamp.from(from), Timestamp.from(until));
    }
    /**
     * 하는 일: 평가에 써도 되는 가장 최근 스냅샷 하나를 골라요. 조건은 기준시각이 cutoff 이하이면서 10분 이내이고,
     * 계산 완료시각이 availableBy 이하인 것이에요(나중에 계산된 자료를 과거 평가에 섞지 않으려는 것).
     * 받는 것: 종목, 기준시각 cutoff, 입력 허용 마감 시각이에요. 돌려주는 것: Snapshot 또는 null이에요.
     */
    public Snapshot available(String symbol, Instant cutoff, Instant availableBy) {
        return jdbc.query("SELECT payload FROM closing_trajectory_snapshot WHERE symbol=? AND evaluation_time<=? AND evaluation_time>=? AND evaluated_at<=? ORDER BY evaluation_time DESC LIMIT 1",
                (rs, n) -> read(rs.getString(1), Snapshot.class), symbol, Timestamp.from(cutoff),
                Timestamp.from(cutoff.minusSeconds(600)), Timestamp.from(availableBy)).stream().findFirst().orElse(null);
    }
    /** 하는 일: 스냅샷에 다음 날 오전 결과(outcome)를 채워 넣어요(덮어씀). 받는 것: 스냅샷, 결과 객체. 돌려주는 것: 없어요. */
    public void outcome(Snapshot row, Object outcome) {
        jdbc.update("UPDATE closing_trajectory_snapshot SET outcome=? WHERE symbol=? AND evaluation_time=?",
                json(outcome), row.symbol(), Timestamp.from(row.timestamp()));
    }
    /** 하는 일: 기간 안 스냅샷의 종목·기준시각·오전 결과만 읽어요. 받는 것: 시작(포함), 끝(제외). 돌려주는 것: Map 목록이에요. */
    public List<Map<String, Object>> outcomes(Instant from, Instant until) {
        return jdbc.query("SELECT symbol,evaluation_time,outcome FROM closing_trajectory_snapshot WHERE evaluation_time>=? AND evaluation_time<? ORDER BY evaluation_time,symbol",
                (rs, n) -> fields("symbol", rs.getString(1), "evaluationTime", rs.getTimestamp(2).toInstant(),
                        "outcome", rs.getString(3) == null ? null : read(rs.getString(3), Object.class)), Timestamp.from(from), Timestamp.from(until));
    }
    /** 하는 일: 객체를 JSON 문자열로 바꿔요. 받는 것: 객체. 돌려주는 것: JSON 문자열이에요(실패하면 예외). */
    private String json(Object row) {
        try { return mapper.writeValueAsString(row); }
        catch (Exception e) { throw new IllegalStateException("Trajectory serialization failed", e); }
    }
    /** 하는 일: JSON 문자열을 지정한 타입 객체로 되돌려요. 받는 것: JSON, 타입. 돌려주는 것: 객체예요(실패하면 예외). */
    private <T> T read(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (Exception e) { throw new IllegalStateException("Trajectory read failed", e); }
    }
}
