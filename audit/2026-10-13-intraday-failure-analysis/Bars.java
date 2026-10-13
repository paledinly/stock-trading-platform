import java.nio.file.*; import java.sql.*; import java.util.*; import java.io.*; import java.time.*;
/** Read-only: 5M bars for each (stock, KST session) of the given recommendations. Credentials never printed. */
public class Bars { public static void main(String[] a) throws Exception {
  Map<String,String> env=new HashMap<>();
  for(String l:Files.readAllLines(Path.of(a[0]))){ if(l.isBlank()||l.strip().startsWith("#")||!l.contains("="))continue; int i=l.indexOf('='); String v=l.substring(i+1).trim();
    if(v.length()>1&&(v.startsWith("\"")&&v.endsWith("\"")||v.startsWith("'")&&v.endsWith("'")))v=v.substring(1,v.length()-1); env.put(l.substring(0,i).trim(),v);}
  ZoneId kst=ZoneId.of("Asia/Seoul");
  Set<List<String>> keys=new LinkedHashSet<>();
  for(String l:Files.readAllLines(Path.of(a[1]))){ String[] f=l.split("\t"); keys.add(List.of(f[1], Instant.parse(f[2]).atZone(kst).toLocalDate().toString())); }
  Properties p=new Properties(); p.setProperty("user",env.get("DB_USERNAME")); p.setProperty("password",env.get("DB_PASSWORD"));
  try(Connection c=DriverManager.getConnection(env.get("DB_URL"),p); PrintWriter w=new PrintWriter(Files.newBufferedWriter(Path.of(a[2])))){
    c.setReadOnly(true); c.setAutoCommit(false); int n=0;
    try(PreparedStatement ps=c.prepareStatement("select c.start_time, c.open, c.high, c.low, c.close from stock_candle c join stock s on s.id=c.stock_id where s.stock_code=? and c.timeframe='5M' and c.start_time >= ? and c.start_time < ? order by c.start_time")){
      for(List<String> k:keys){ LocalDate d=LocalDate.parse(k.get(1));
        ps.setString(1,k.get(0)); ps.setTimestamp(2,Timestamp.from(d.atTime(9,0).atZone(kst).toInstant())); ps.setTimestamp(3,Timestamp.from(d.atTime(15,35).atZone(kst).toInstant()));
        try(ResultSet r=ps.executeQuery()){ while(r.next()){ w.println(k.get(0)+"\t"+k.get(1)+"\t"+r.getTimestamp(1).toInstant()+"\t"+r.getString(2)+"\t"+r.getString(3)+"\t"+r.getString(4)+"\t"+r.getString(5)); n++; } } } }
    c.rollback(); System.out.println("pairs="+keys.size()+" bars="+n);
  } } }
