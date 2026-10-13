import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Read-only export for the ADR-008 closing v9 judge.
 *
 * Usage: java -cp postgresql.jar ExportClosingV9.java <path/to/.env> <outDir> [strategyVersion]
 *
 * Reads DB_URL / DB_USERNAME / DB_PASSWORD from the given .env file. Credentials and other .env
 * values are never printed. The connection is read-only, the transaction is READ ONLY and is
 * rolled back at the end. Only closing tables, trading_calendar_day, stock and 5M stock_candle rows
 * of ENTERED paper-entry stocks in the ten calendar days after the recommendation date are read.
 *
 * Output (TSV, header row): entries.tsv, bars.tsv, calendar.tsv, meta.tsv
 */
public class ExportClosingV9 {
    static final String DEFAULT_VERSION = "closing-recommend-v9_1-1515";

    public static void main(String[] a) throws Exception {
        if (a.length < 2) {
            System.err.println("usage: ExportClosingV9 <envFile> <outDir> [strategyVersion]");
            System.exit(2);
        }
        Map<String, String> env = readEnv(Path.of(a[0]));
        Path out = Path.of(a[1]);
        Files.createDirectories(out);
        String version = a.length > 2 ? a[2] : DEFAULT_VERSION;

        Properties p = new Properties();
        p.setProperty("user", env.get("DB_USERNAME"));
        p.setProperty("password", env.get("DB_PASSWORD"));
        p.setProperty("connectTimeout", "15");
        p.setProperty("socketTimeout", "120");
        p.setProperty("readOnly", "true");

        // Disposition (SELECTED/WATCH/EXCLUDED) comes from the run's stored evaluation snapshot.
        // One row per paper entry of an official FORWARD run of the given strategy version.
        // The recommendation join is by (run_id, stock_id): only SELECTED recommendations exist in
        // closing_recommendation; other evaluated candidates also get paper entries (secondary sample).
        String entries = """
            select run.id run_id, run.recommendation_date, run.strategy_version, run.execution_mode,
                   run.evaluated_as_of, run.completed_at run_completed_at, run.request_key,
                   pe.stock_code, s.id stock_id, coalesce(s.is_etf,false) is_etf,
                   pe.status entry_status, pe.completed_at, pe.deadline, pe.source_at, pe.received_at,
                   pe.entered_at, pe.price entry_price,
                   r.id rec_id, r.rank_no, r.recommendation_score, r.buy_reference_price, r.status rec_status,
                   r.strategy_version rec_strategy_version,
                   op.id perf_id, op.status perf_status, op.calculation_version, op.expected_session_date,
                   op.next_trading_date, op.observed_through, op.target_rate, op.stop_rate,
                   op.virtual_entry_at, op.virtual_entry_price, op.execution_exit_at, op.execution_exit_price,
                   op.gross_return_rate, op.net_return_rate, op.execution_exit_reason, op.execution_ambiguous,
                   op.execution_model_version, op.cost_assumption, op.cost_status,
                   ev.disposition, ev.decision_reason, ev.final_score, ev.reference_price, ev.candidate_source
            from closing_paper_entry pe
            join closing_recommendation_run run on run.id = pe.run_id
            left join lateral (
              select e->>'disposition' disposition, e->>'decisionReason' decision_reason,
                     e->>'finalScore' final_score, e->>'referencePrice' reference_price,
                     e->>'candidateSource' candidate_source
              from jsonb_array_elements(case when run.response_snapshot like '{%'
                     then run.response_snapshot::jsonb->'evaluations' else '[]'::jsonb end) e
              where e->>'stockCode' = pe.stock_code limit 1) ev on true
            left join stock s on s.stock_code = pe.stock_code
            left join closing_recommendation r on r.run_id = pe.run_id and r.stock_id = s.id
            left join overnight_performance op on op.closing_recommendation_id = r.id
            where run.strategy_version = ? and run.execution_mode = 'FORWARD'
            order by run.recommendation_date, run.id, r.rank_no nulls last, pe.stock_code""";

        // Five-minute candles of ENTERED stocks in the ten calendar days after the recommendation date,
        // regular-session start times only (09:00..15:30 KST). The judge selects the next trading day.
        String bars = """
            with e as (
              select distinct s.id stock_id, pe.stock_code, run.recommendation_date d
              from closing_paper_entry pe
              join closing_recommendation_run run on run.id = pe.run_id
              join stock s on s.stock_code = pe.stock_code
              where run.strategy_version = ? and run.execution_mode = 'FORWARD' and pe.status = 'ENTERED')
            select distinct on (c.stock_id, c.start_time)
                   e.stock_code, c.stock_id, c.start_time, c.open, c.high, c.low, c.close, c.volume,
                   c.is_final, c.revision, c.source, c.updated_at
            from e join stock_candle c
              on c.stock_id = e.stock_id and c.timeframe = '5M'
             and c.start_time >= ((e.d + 1)::timestamp at time zone 'Asia/Seoul')
             and c.start_time <  ((e.d + 11)::timestamp at time zone 'Asia/Seoul')
            where (c.start_time at time zone 'Asia/Seoul')::time between time '09:00' and time '15:30'
            order by c.stock_id, c.start_time""";

        String calendar = """
            select trade_date, market_open, source, fetched_at from trading_calendar_day
            where trade_date >= coalesce((select min(recommendation_date) from closing_recommendation_run
                                           where strategy_version = ? and execution_mode = 'FORWARD'), current_date)
            order by trade_date""";

        try (Connection c = DriverManager.getConnection(env.get("DB_URL"), p)) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
            }
            Instant dbNow;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select now()")) {
                rs.next();
                dbNow = rs.getTimestamp(1).toInstant();
            }
            int ne = dump(c, entries, version, out.resolve("entries.tsv"));
            int nb = dump(c, bars, version, out.resolve("bars.tsv"));
            int nc = dump(c, calendar, version, out.resolve("calendar.tsv"));
            // Version/mode inventory to show what was excluded (LEGACY/REPLAY/other versions).
            int ni = dump(c, """
                select strategy_version, execution_mode, count(*) runs, min(recommendation_date) first_date,
                       max(recommendation_date) last_date, ? :: text requested_version
                from closing_recommendation_run group by strategy_version, execution_mode order by 1, 2""",
                    version, out.resolve("run_inventory.tsv"));
            c.rollback();
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("meta.tsv")))) {
                w.println("key\tvalue");
                w.println("exported_at\t" + dbNow);
                w.println("strategy_version\t" + version);
                w.println("entries_rows\t" + ne);
                w.println("bars_rows\t" + nb);
                w.println("calendar_rows\t" + nc);
            }
            System.out.println("exported_at=" + dbNow + " entries=" + ne + " bars=" + nb
                    + " calendar=" + nc + " inventory=" + ni);
        }
    }

    static int dump(Connection c, String sql, String version, Path file) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery();
                 PrintWriter w = new PrintWriter(Files.newBufferedWriter(file))) {
                int n = rs.getMetaData().getColumnCount();
                List<String> h = new ArrayList<>();
                for (int i = 1; i <= n; i++) h.add(rs.getMetaData().getColumnLabel(i));
                w.println(String.join("\t", h));
                int rows = 0;
                while (rs.next()) {
                    List<String> r = new ArrayList<>();
                    for (int i = 1; i <= n; i++) {
                        Object o = rs.getObject(i);
                        String v = o instanceof Timestamp t ? t.toInstant().toString() : rs.getString(i);
                        r.add(v == null ? "" : v.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
                    }
                    w.println(String.join("\t", r));
                    rows++;
                }
                return rows;
            }
        }
    }

    static Map<String, String> readEnv(Path path) throws IOException {
        Map<String, String> env = new HashMap<>();
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank() || line.strip().startsWith("#") || !line.contains("=")) continue;
            int i = line.indexOf('=');
            String v = line.substring(i + 1).trim();
            if (v.length() > 1 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))))
                v = v.substring(1, v.length() - 1);
            env.put(line.substring(0, i).trim(), v);
        }
        for (String k : List.of("DB_URL", "DB_USERNAME", "DB_PASSWORD"))
            if (env.get(k) == null || env.get(k).isBlank()) throw new IllegalStateException("missing " + k + " in env file");
        return env;
    }
}
