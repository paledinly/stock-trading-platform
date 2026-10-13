import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Read-only export of 1D stock candles for the overnight study.
 *
 * Usage: java -cp postgresql.jar ExportDaily.java <path/to/.env> <out.tsv>
 *
 * Reads only DB_URL / DB_USERNAME / DB_PASSWORD from the env file and prints no value from it.
 * Read-only connection, READ ONLY transaction, rollback at the end. About 19k rows.
 */
public class ExportDaily {
    public static void main(String[] a) throws Exception {
        if (a.length < 2) {
            System.err.println("usage: ExportDaily <envFile> <out.tsv>");
            System.exit(2);
        }
        Map<String, String> env = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(a[0]))) {
            if (line.isBlank() || line.strip().startsWith("#") || !line.contains("=")) continue;
            int i = line.indexOf('=');
            String v = line.substring(i + 1).trim();
            if (v.length() > 1 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))))
                v = v.substring(1, v.length() - 1);
            env.put(line.substring(0, i).trim(), v);
        }
        Properties p = new Properties();
        p.setProperty("user", env.get("DB_USERNAME"));
        p.setProperty("password", env.get("DB_PASSWORD"));
        p.setProperty("connectTimeout", "15");
        p.setProperty("socketTimeout", "120");
        String q = """
            select s.stock_code, coalesce(s.is_etf,false) is_etf,
                   (c.start_time at time zone 'Asia/Seoul')::date trade_date,
                   c.open, c.high, c.low, c.close, c.volume, c.trading_value, c.is_final
            from stock_candle c join stock s on s.id = c.stock_id
            where c.timeframe = '1D'
            order by s.stock_code, c.start_time""";
        try (Connection c = DriverManager.getConnection(env.get("DB_URL"), p)) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
                st.setFetchSize(5000);
                try (ResultSet rs = st.executeQuery(q);
                     PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(a[1])))) {
                    int n = rs.getMetaData().getColumnCount();
                    List<String> h = new ArrayList<>();
                    for (int i = 1; i <= n; i++) h.add(rs.getMetaData().getColumnLabel(i));
                    w.println(String.join("\t", h));
                    int rows = 0;
                    while (rs.next()) {
                        List<String> r = new ArrayList<>();
                        for (int i = 1; i <= n; i++) {
                            String v = rs.getString(i);
                            r.add(v == null ? "" : v);
                        }
                        w.println(String.join("\t", r));
                        rows++;
                    }
                    System.out.println("rows=" + rows);
                }
            }
            c.rollback();
        }
    }
}
