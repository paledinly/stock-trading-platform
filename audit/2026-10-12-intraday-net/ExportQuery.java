import java.nio.file.*; import java.sql.*; import java.util.*; import java.io.*;
/** Read-only export of intraday recommendation price fields. Credentials never printed. */
public class ExportQuery {
 public static void main(String[] a) throws Exception {
  Map<String,String> env=new HashMap<>();
  for(String line:Files.readAllLines(Path.of(a[0]))){ if(line.isBlank()||line.strip().startsWith("#")||!line.contains("="))continue;
   int i=line.indexOf('='); String v=line.substring(i+1).trim();
   if(v.length()>1&&((v.startsWith("\"")&&v.endsWith("\""))||(v.startsWith("'")&&v.endsWith("'"))))v=v.substring(1,v.length()-1);
   env.put(line.substring(0,i).trim(),v);}
  Properties p=new Properties(); p.setProperty("user",env.get("DB_USERNAME")); p.setProperty("password",env.get("DB_PASSWORD"));
  p.setProperty("connectTimeout","15"); p.setProperty("socketTimeout","120");
  String q=Files.readString(Path.of(a[2])); String unused="""
   select r.session_date, r.recommended_at, r.stock_code, coalesce(s.is_etf,false) is_etf, r.setup,
    (r.snapshot::jsonb->>'price') price, (r.snapshot::jsonb#>>'{plan,entryTo}') entry_to,
    (r.snapshot::jsonb#>>'{plan,stop}') stop, (r.snapshot::jsonb#>>'{plan,target1}') target1,
    (r.snapshot::jsonb#>>'{policy,costs,sellTaxPercent}') policy_tax,
    o->>'status' status, o->>'entryReferencePrice' entry_ref, o->>'entryPrice' entry_fill,
    o->>'exitPrice' exit_price, o->>'grossReturn' gross, o->>'netReturn' net, o->>'uncertain' uncertain
   from (select *, outcome::jsonb o from intraday_recommendation) r left join stock s on s.stock_code=r.stock_code
   order by r.recommended_at""";
  try(Connection c=DriverManager.getConnection(env.get("DB_URL"),p)){
   c.setReadOnly(true); c.setAutoCommit(false);
   try(Statement st=c.createStatement()){ st.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
    try(ResultSet rs=st.executeQuery(q); PrintWriter w=new PrintWriter(Files.newBufferedWriter(Path.of(a[1])))){
     int n=rs.getMetaData().getColumnCount(); List<String> h=new ArrayList<>(); for(int i=1;i<=n;i++)h.add(rs.getMetaData().getColumnLabel(i));
     w.println(String.join("\t",h)); int rows=0;
     while(rs.next()){List<String> r=new ArrayList<>(); for(int i=1;i<=n;i++){String v=rs.getString(i); r.add(v==null?"":v);} w.println(String.join("\t",r)); rows++;}
     System.out.println("rows="+rows);
    }}
   c.rollback();
  }
 }
}
