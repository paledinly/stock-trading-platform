import java.io.*; import java.nio.charset.StandardCharsets; import java.nio.file.*; import java.security.*; import java.sql.*;
import java.time.LocalDate; import java.util.*; import java.util.zip.GZIPOutputStream;

/**
 * Read-only archive of intraday_input, one gzip JSONL file per session, before the table is emptied.
 * Each line: {"id":..,"sessionDate":"..","stockCode":"..","receivedAt":"..","evaluatedAt":"..","payload":<original JSON>}.
 * Writes <dir>/manifest.tsv (session, rows in file, rows in DB at export, min id, max id, sha256). Credentials never printed.
 * args: .env path, output dir, session dates...
 */
public class ExportIntradayInput {
  public static void main(String[] a) throws Exception {
    Map<String,String> env = new HashMap<>();
    for (String l : Files.readAllLines(Path.of(a[0]))) {
      if (l.isBlank() || l.strip().startsWith("#") || !l.contains("=")) continue;
      int i = l.indexOf('='); String v = l.substring(i + 1).trim();
      if (v.length() > 1 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) v = v.substring(1, v.length() - 1);
      env.put(l.substring(0, i).trim(), v);
    }
    Path dir = Files.createDirectories(Path.of(a[1]));
    Properties p = new Properties(); p.setProperty("user", env.get("DB_USERNAME")); p.setProperty("password", env.get("DB_PASSWORD"));
    p.setProperty("connectTimeout", "15"); p.setProperty("socketTimeout", "300");
    try (Connection c = DriverManager.getConnection(env.get("DB_URL"), p)) {
      c.setReadOnly(true); c.setAutoCommit(false);
      for (int k = 2; k < a.length; k++) {
        LocalDate date = LocalDate.parse(a[k]);
        long dbRows, upper;
        try (PreparedStatement ps = c.prepareStatement("select count(*), coalesce(max(id),0) from intraday_input where session_date = ?")) {
          ps.setObject(1, date); try (ResultSet r = ps.executeQuery()) { r.next(); dbRows = r.getLong(1); upper = r.getLong(2); }
        }
        Path file = dir.resolve("intraday_input_" + date + ".jsonl.gz");
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long rows = 0, after = 0, first = -1;
        try (var out = new DigestOutputStream(Files.newOutputStream(file), sha);
             var gz = new GZIPOutputStream(out, 1 << 16);
             var w = new BufferedWriter(new OutputStreamWriter(gz, StandardCharsets.UTF_8), 1 << 20);
             PreparedStatement ps = c.prepareStatement("select id, stock_code, received_at, evaluated_at, payload from intraday_input "
                 + "where session_date = ? and id > ? and id <= ? order by id limit 5000")) {
          while (true) {
            ps.setObject(1, date); ps.setLong(2, after); ps.setLong(3, upper); int page = 0;
            try (ResultSet r = ps.executeQuery()) {
              while (r.next()) {
                long id = r.getLong(1); if (first < 0) first = id;
                w.write("{\"id\":" + id + ",\"sessionDate\":\"" + date + "\",\"stockCode\":\"" + r.getString(2)
                    + "\",\"receivedAt\":\"" + r.getTimestamp(3).toInstant() + "\",\"evaluatedAt\":\"" + r.getTimestamp(4).toInstant()
                    + "\",\"payload\":" + r.getString(5) + "}\n");
                after = id; rows++; page++;
              }
            }
            if (page == 0) break;
          }
        }
        String hash = HexFormat.of().formatHex(sha.digest());
        String line = date + "\t" + rows + "\t" + dbRows + "\t" + first + "\t" + upper + "\t" + hash;
        Files.writeString(dir.resolve("manifest.tsv"), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.printf("%s rows=%d db=%d %s sizeMB=%.1f%n", date, rows, dbRows, rows == dbRows ? "OK" : "MISMATCH", Files.size(file) / 1e6);
      }
      c.rollback();
    }
  }
}
