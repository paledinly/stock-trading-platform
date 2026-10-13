import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Read-only TSV export of one SQL query.
 * Usage: java -cp postgresql.jar ExportQuery.java <path/to/.env> <query.sql> <out.tsv>
 * Reads only DB_URL / DB_USERNAME / DB_PASSWORD from the env file and never prints them.
 * Connection is read-only, the transaction is READ ONLY and rolled back at the end.
 */
public class ExportQuery {
    public static void main(String[] a) throws Exception {
        Map<String, String> env = readEnv(Path.of(a[0]));
        String sql = Files.readString(Path.of(a[1]));
        Properties p = new Properties();
        p.setProperty("user", env.get("DB_USERNAME"));
        p.setProperty("password", env.get("DB_PASSWORD"));
        p.setProperty("connectTimeout", "15");
        p.setProperty("socketTimeout", "180");
        p.setProperty("readOnly", "true");
        try (Connection c = DriverManager.getConnection(env.get("DB_URL"), p)) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
                st.setFetchSize(200);
                try (ResultSet rs = st.executeQuery(sql);
                     PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(a[2])))) {
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
                    System.out.println("rows=" + rows);
                }
            }
            c.rollback();
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
            if (env.get(k) == null || env.get(k).isBlank()) throw new IllegalStateException("missing " + k);
        return env;
    }
}
