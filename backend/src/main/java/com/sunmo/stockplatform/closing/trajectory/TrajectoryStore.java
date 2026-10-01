package com.sunmo.stockplatform.closing.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.*;

@Repository
public class TrajectoryStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public TrajectoryStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    public void minute(Minute row) {
        jdbc.update("INSERT INTO closing_minute_feature VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                row.symbol(), Timestamp.from(row.start()), Timestamp.from(row.finalizedAt()), json(row));
    }
    public void context(Context row) {
        jdbc.update("INSERT INTO closing_context_observation VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                row.kind(), row.symbol(), row.scope(), Timestamp.from(row.receivedAt()), json(row));
    }
    public boolean snapshot(Snapshot row) {
        return jdbc.update("INSERT INTO closing_trajectory_snapshot(symbol,evaluation_time,evaluated_at,payload) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                row.symbol(), Timestamp.from(row.timestamp()), Timestamp.from(row.evaluatedAt()), json(row)) == 1;
    }
    public List<Minute> minutes(String symbol, Instant from, Instant until) {
        return jdbc.query("SELECT payload FROM closing_minute_feature WHERE symbol=? AND start_time>=? AND start_time<? ORDER BY start_time",
                (rs, n) -> read(rs.getString(1), Minute.class), symbol, Timestamp.from(from), Timestamp.from(until));
    }
    public List<Context> contexts(String symbol, String market, Instant from, Instant until) {
        return jdbc.query("SELECT payload FROM closing_context_observation WHERE symbol IN (?,?) AND received_at>=? AND received_at<=? ORDER BY received_at",
                (rs, n) -> read(rs.getString(1), Context.class), symbol, market, Timestamp.from(from), Timestamp.from(until));
    }
    public List<Snapshot> snapshots(Instant from, Instant until) {
        return jdbc.query("SELECT payload FROM closing_trajectory_snapshot WHERE evaluation_time>=? AND evaluation_time<? ORDER BY evaluation_time,symbol",
                (rs, n) -> read(rs.getString(1), Snapshot.class), Timestamp.from(from), Timestamp.from(until));
    }
    public Snapshot available(String symbol, Instant cutoff, Instant availableBy) {
        return jdbc.query("SELECT payload FROM closing_trajectory_snapshot WHERE symbol=? AND evaluation_time<=? AND evaluation_time>=? AND evaluated_at<=? ORDER BY evaluation_time DESC LIMIT 1",
                (rs, n) -> read(rs.getString(1), Snapshot.class), symbol, Timestamp.from(cutoff),
                Timestamp.from(cutoff.minusSeconds(600)), Timestamp.from(availableBy)).stream().findFirst().orElse(null);
    }
    public void outcome(Snapshot row, Object outcome) {
        jdbc.update("UPDATE closing_trajectory_snapshot SET outcome=? WHERE symbol=? AND evaluation_time=?",
                json(outcome), row.symbol(), Timestamp.from(row.timestamp()));
    }
    public List<Map<String, Object>> outcomes(Instant from, Instant until) {
        return jdbc.query("SELECT symbol,evaluation_time,outcome FROM closing_trajectory_snapshot WHERE evaluation_time>=? AND evaluation_time<? ORDER BY evaluation_time,symbol",
                (rs, n) -> fields("symbol", rs.getString(1), "evaluationTime", rs.getTimestamp(2).toInstant(),
                        "outcome", rs.getString(3) == null ? null : read(rs.getString(3), Object.class)), Timestamp.from(from), Timestamp.from(until));
    }
    private String json(Object row) {
        try { return mapper.writeValueAsString(row); }
        catch (Exception e) { throw new IllegalStateException("Trajectory serialization failed", e); }
    }
    private <T> T read(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (Exception e) { throw new IllegalStateException("Trajectory read failed", e); }
    }
}
