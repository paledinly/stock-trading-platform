import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Read-only export of adjusted daily bars (daily_adjusted_candle) with stock codes.
 *
 * Usage: java -cp postgresql.jar ExportAdjusted.java <path/to/.env> <out.tsv>
 *
 * Reads only DB_URL / DB_USERNAME / DB_PASSWORD from the env file and prints no value from it.
 * Read-only connection, READ ONLY transaction, rollback at the end. About 400k rows.
 * The first line is a comment with the export time and row count is printed to stdout.
 */
public class ExportAdjusted {
    public static void main(String[] a) throws Exception {
        if (a.length < 2) {
            System.err.println("usage: ExportAdjusted <envFile> <out.tsv>");
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
        p.setProperty("socketTimeout", "300");
        String q = """
            select s.stock_code, c.trade_date, c.open_price, c.high_price, c.low_price, c.close_price,
                   c.volume, c.trading_value
              from daily_adjusted_candle c join stock s on s.id = c.stock_id
             order by s.stock_code, c.trade_date""";
        Path out = Path.of(a[1]);
        Files.createDirectories(out.toAbsolutePath().getParent());
        long rows = 0;
        try (Connection c = DriverManager.getConnection(env.get("DB_URL"), p)) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("set transaction isolation level repeatable read, read only");
                s.setFetchSize(5000);
                try (ResultSet r = s.executeQuery(q);
                     BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                    w.write("# exported_at=" + Instant.now() + "\n");
                    w.write("stock_code\ttrade_date\topen\thigh\tlow\tclose\tvolume\ttrading_value\n");
                    while (r.next()) {
                        w.write(r.getString(1) + "\t" + r.getString(2) + "\t" + r.getBigDecimal(3).toPlainString() + "\t"
                                + r.getBigDecimal(4).toPlainString() + "\t" + r.getBigDecimal(5).toPlainString() + "\t"
                                + r.getBigDecimal(6).toPlainString() + "\t" + r.getLong(7) + "\t"
                                + r.getBigDecimal(8).toPlainString() + "\n");
                        rows++;
                    }
                }
            }
            c.rollback();
        }
        System.out.println("rows=" + rows);
    }
}
