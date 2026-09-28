import com.sunmo.stockplatform.candle.application.*;
import com.sunmo.stockplatform.market.domain.MarketTick;
import java.math.BigDecimal;
import java.time.*;
public class AuditReproduction {
 static MarketTick tick(String time,long sequence,long cumulative,long trade){return new MarketTick("TEST",LocalDate.parse("2026-09-23"),Instant.parse("2026-09-23T"+time+"Z"),BigDecimal.valueOf(100),trade,cumulative,BigDecimal.valueOf(cumulative*100),sequence);}
 public static void main(String[] args){
  var a=new FiveMinuteCandleAggregator(Duration.ofSeconds(2));
  try {a.accept(tick("06:30:00",1,100,1));throw new AssertionError("15:30 unexpectedly accepted");}catch(IllegalArgumentException e){System.out.println("CONFIRMED 15:30 KST rejected: "+e.getMessage());}
  a.accept(tick("05:55:00",2,1000,100));a.accept(tick("05:59:50",3,1500,50));
  var first=a.flush(Instant.parse("2026-09-23T06:00:03Z")).getFirst();
  a.accept(tick("05:59:59",4,1550,50));
  var revised=a.flush(Instant.parse("2026-09-23T06:00:05Z")).getFirst();
  if(!first.startTime().equals(revised.startTime())||first.volume()!=600||revised.volume()!=50)throw new AssertionError();
  System.out.println("CONFIRMED same bucket re-emitted after flush: original volume="+first.volume()+", replacement volume="+revised.volume()+", revisions="+first.revision()+"/"+revised.revision());
 }
}
