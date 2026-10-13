import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sunmo.stockplatform.intraday.*;
import com.sunmo.stockplatform.intraday.IntradayModel.*;
import java.nio.file.*; import java.sql.*; import java.time.*; import java.util.*;

/**
 * Read-only replay of IntradayExitComparison over the stored receipt ledger, outside the running backend.
 * Same code path as POST /api/v1/intraday/exit-comparison; writes nothing to the database. Credentials never printed.
 * args: .env path, session date, variants json, output json
 */
public class RunComparison {
  public static void main(String[] a) throws Exception {
    Map<String,String> env = new HashMap<>();
    for (String line : Files.readAllLines(Path.of(a[0]))) {
      if (line.isBlank() || line.strip().startsWith("#") || !line.contains("=")) continue;
      int i = line.indexOf('='); String v = line.substring(i + 1).trim();
      if (v.length() > 1 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) v = v.substring(1, v.length() - 1);
      env.put(line.substring(0, i).trim(), v);
    }
    ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    LocalDate date = LocalDate.parse(a[1]);
    var request = json.readValue(Path.of(a[2]).toFile(), IntradayExitComparison.Request.class);
    Properties p = new Properties(); p.setProperty("user", env.get("DB_USERNAME")); p.setProperty("password", env.get("DB_PASSWORD"));
    p.setProperty("connectTimeout", "15"); p.setProperty("socketTimeout", "300");
    Instant asOf = Instant.now(); long bytes = 0, rows = 0;
    try (Connection c = DriverManager.getConnection(env.get("DB_URL"), p)) {
      c.setReadOnly(true); c.setAutoCommit(false);
      try (Statement st = c.createStatement()) { st.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY"); }
      List<Result> paper = new ArrayList<>();
      try (PreparedStatement ps = c.prepareStatement(
          "select snapshot, outcome from intraday_recommendation where session_date = ? order by recommended_at")) {
        ps.setObject(1, date);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) paper.add(new Result(json.readValue(rs.getString(1), Signal.class), json.readValue(rs.getString(2), Outcome.class)));
        }
      }
      var comparison = new IntradayExitComparison(paper.stream().filter(r -> !r.signal().recommendedAt().isAfter(asOf)).toList(), request);
      long upper;
      try (PreparedStatement ps = c.prepareStatement("select coalesce(max(id), 0) from intraday_input where session_date = ?")) {
        ps.setObject(1, date); try (ResultSet rs = ps.executeQuery()) { rs.next(); upper = rs.getLong(1); }
      }
      long after = 0;
      try (PreparedStatement ps = c.prepareStatement(
          "select id, payload from intraday_input where session_date = ? and id > ? order by id limit 2000")) {
        while (after < upper) {
          ps.setObject(1, date); ps.setLong(2, after); int page = 0;
          try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              long id = rs.getLong(1); if (id > upper) { after = upper; break; }
              String payload = rs.getString(2); bytes += payload.length(); rows++; page++;
              Input input = json.readValue(payload, Input.class);
              if (!input.evaluatedAt().isAfter(asOf)) comparison.accept(input);
              after = id;
            }
          }
          if (page == 0) break;
        }
      }
      c.rollback();
      json.writerWithDefaultPrettyPrinter().writeValue(Path.of(a[3]).toFile(), comparison.finish(asOf));
    }
    System.out.printf("date=%s recommendations-read inputs=%d payloadMB=%.1f%n", date, rows, bytes / 1e6);
  }
}
