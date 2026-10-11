import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.io.*;

/** Read-only, consistent DB snapshot. Credentials never enter output artifacts. */
public class ExportEvidence {
 public static void main(String[] args) throws Exception {
  Path out=Path.of("audit/2026-10-08-intraday"); Files.createDirectories(out);
  Map<String,String> env=new HashMap<>();
  for(String line:Files.readAllLines(Path.of(".env"))) {
   if(line.isBlank()||line.stripLeading().startsWith("#")||!line.contains("="))continue;
   int i=line.indexOf('='); String v=line.substring(i+1).trim();
   if(v.length()>1&&((v.startsWith("\"")&&v.endsWith("\""))||(v.startsWith("'")&&v.endsWith("'")))) v=v.substring(1,v.length()-1);
   env.put(line.substring(0,i).trim(),v);
  }
  Properties p=new Properties(); p.setProperty("user",env.getOrDefault("DB_USERNAME","stock"));
  p.setProperty("password",env.getOrDefault("DB_PASSWORD","change-me")); p.setProperty("connectTimeout","15");p.setProperty("socketTimeout","240");
  try(Connection c=DriverManager.getConnection(env.getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/stock_platform"),p)) {
   c.setReadOnly(true); c.setAutoCommit(false);
   try(Statement s=c.createStatement()){s.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");}
   if(args.length>0 && args[0].equals("micro")) {
    export(c,out,"microstructure.tsv","select kind,symbol,start_time,finalized_at,payload from closing_microstructure_minute where start_time>='2026-10-07T15:00:00Z' and start_time<'2026-10-08T15:00:00Z' order by symbol,start_time");
    c.rollback();return;
   }
   export(c,out,"metadata.tsv","select now() as snapshot_at, current_date as server_date");
   export(c,out,"columns.tsv","select table_name,column_name,data_type from information_schema.columns where table_schema='public' and (table_name like 'closing_%' or table_name like 'intraday_%' or table_name='stock_candle') order by table_name,ordinal_position");
   export(c,out,"recommendations.tsv","select r.session_date,r.id,r.stock_code,r.snapshot,r.outcome,r.tracking_complete,s.market_type,s.stock_name from intraday_recommendation r left join stock s on s.stock_code=r.stock_code where r.session_date>='2026-08-24' order by r.recommended_at,r.id");
   export(c,out,"contexts.tsv","select kind,symbol,scope,received_at,payload from closing_context_observation where received_at>='2026-10-07T15:00:00Z' and received_at<'2026-10-08T15:00:00Z' order by received_at");
   export(c,out,"minutes.tsv","select symbol,start_time,finalized_at,payload from closing_minute_feature where start_time>='2026-10-07T15:00:00Z' and start_time<'2026-10-08T15:00:00Z' and symbol in(select distinct stock_code from intraday_recommendation where session_date='2026-10-08') order by symbol,start_time");
   export(c,out,"daily.tsv","select s.stock_code,c.start_time,c.close,c.created_at,c.source from stock_candle c join stock s on s.id=c.stock_id where c.timeframe='1D' and c.start_time>='2026-09-25T15:00:00Z' and c.start_time<'2026-10-07T15:00:00Z' and s.stock_code in(select distinct stock_code from intraday_recommendation where session_date='2026-10-08') order by s.stock_code,c.start_time");
   long first=0,last=0;
   try(Statement s=c.createStatement();ResultSet r=s.executeQuery("select (select id from intraday_input where session_date='2026-10-08' order by id limit 1),(select id from intraday_input where session_date='2026-10-08' order by id desc limit 1)")){if(r.next()){first=r.getLong(1);last=r.getLong(2);}}
   Files.writeString(out.resolve("input_bounds.txt"),first+"\t"+last+"\n");
   String tickSql="""
    select id,stock_code,received_at,evaluated_at,
      j#>>'{observation,tick,occurredAt}' as source_at,j#>>'{observation,tick,price}' as price,
      j#>>'{observation,tick,tradeVolume}' as trade_volume,j#>>'{observation,tick,cumulativeVolume}' as volume,
      j#>>'{observation,tick,cumulativeTradingValue}' as value,j#>>'{observation,tick,tradeStrength}' as strength,
      j#>>'{observation,tick,highPrice}' as day_high,j#>>'{observation,tick,lowPrice}' as day_low,
      j#>>'{observation,feature,vwap}' as vwap,j->>'eligible' as eligible,j->>'dataLoss' as data_loss,
      j#>>'{observation,tick,cumulativeBuyVolume}' as buy_volume,j#>>'{observation,tick,cumulativeSellVolume}' as sell_volume
    from (select i.*,payload::jsonb as j from intraday_input i
      where session_date='2026-10-08' and stock_code in
       (select distinct stock_code from intraday_recommendation where session_date='2026-10-08')
       and id>=LOWER_BOUND and id<UPPER_BOUND) t order by id
    """;
   for(long low=first;low<=last&&low>0;low+=50000) {
    export(c,out,"ticks-"+low+".tsv",tickSql.replace("LOWER_BOUND",Long.toString(low)).replace("UPPER_BOUND",Long.toString(low+50000)));
   }
   c.rollback();
  } catch(SQLException e) { System.err.println("Read-only export failed: SQLSTATE="+e.getSQLState()); System.exit(1); }
 }
 static void export(Connection c,Path out,String name,String sql)throws Exception {
  long n=0;
  try(Statement s=c.createStatement();BufferedWriter w=Files.newBufferedWriter(out.resolve(name))) {
   s.setFetchSize(1000);s.setQueryTimeout(240);
   try(ResultSet r=s.executeQuery(sql)){var m=r.getMetaData();
    for(int i=1;i<=m.getColumnCount();i++){if(i>1)w.write('\t');w.write(m.getColumnLabel(i));}w.newLine();
    while(r.next()){for(int i=1;i<=m.getColumnCount();i++){if(i>1)w.write('\t');String v=r.getString(i);w.write(v==null?"":v.replace("\n"," ").replace("\r"," ").replace("\t"," "));}w.newLine();n++;}
   }
  }
  System.out.println(name+": "+n+" rows");
 }
}
