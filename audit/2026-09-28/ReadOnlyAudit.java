import java.nio.file.*;
import java.sql.*;
import java.util.*;
public class ReadOnlyAudit {
 public static void main(String[] args) throws Exception {
  Map<String,String> env=new HashMap<>();
  for(String line:Files.readAllLines(Path.of(".env"))) { if(line.isBlank()||line.startsWith("#")||!line.contains("="))continue; int i=line.indexOf('='); env.put(line.substring(0,i).trim(),line.substring(i+1).trim()); }
  String url=env.getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/stock_platform");
  Properties props=new Properties(); props.setProperty("user",env.getOrDefault("DB_USERNAME","stock")); props.setProperty("password",env.getOrDefault("DB_PASSWORD","change-me")); props.setProperty("connectTimeout","10"); props.setProperty("socketTimeout","90");
  try(Connection c=DriverManager.getConnection(url,props)) { c.setReadOnly(true); c.setAutoCommit(false);
   try(Statement s=c.createStatement()) { s.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY"); s.setQueryTimeout(60);
    for(String sql:Files.readString(Path.of(args[0])).split(";")) { if(sql.isBlank())continue; if(!sql.stripLeading().toLowerCase().startsWith("select")&&!sql.stripLeading().toLowerCase().startsWith("with"))throw new IllegalArgumentException("SELECT only");
     System.out.println("QUERY: "+sql.strip()); try(ResultSet r=s.executeQuery(sql)) { var m=r.getMetaData(); for(int i=1;i<=m.getColumnCount();i++)System.out.print((i>1?"\t":"")+m.getColumnLabel(i));System.out.println(); while(r.next()){ for(int i=1;i<=m.getColumnCount();i++)System.out.print((i>1?"\t":"")+Objects.toString(r.getString(i),"NULL").replace("\n"," ").replace("\r"," ").replace("\t"," ")); System.out.println(); } }
    }
   } finally { c.rollback(); }
  }
 }
}
