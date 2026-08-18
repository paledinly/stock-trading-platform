package com.sunmo.stockplatform.candle;
import com.sunmo.stockplatform.candle.application.FiveMinuteCandleAggregator;
import com.sunmo.stockplatform.market.domain.MarketTick;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class FiveMinuteCandleAggregatorTest {
 private final FiveMinuteCandleAggregator aggregator=new FiveMinuteCandleAggregator(Duration.ofSeconds(2));
 @Test void aggregatesOhlcvAndClosesAtNextBoundary(){var first=tick("2026-08-18T00:00:01Z","100",5,5,"500",1);var second=tick("2026-08-18T00:04:59Z","110",3,8,"830",2);var next=tick("2026-08-18T00:05:00Z","105",2,10,"1040",3);aggregator.accept(first);var update=aggregator.accept(second).getFirst();assertThat(update.open()).isEqualByComparingTo("100");assertThat(update.high()).isEqualByComparingTo("110");assertThat(update.volume()).isEqualTo(8);var boundary=aggregator.accept(next);assertThat(boundary.getFirst().finalCandle()).isTrue();assertThat(boundary.getFirst().close()).isEqualByComparingTo("110");assertThat(boundary.get(1).startTime()).isEqualTo(Instant.parse("2026-08-18T00:05:00Z"));}
 @Test void ignoresDuplicateSequence(){aggregator.accept(tick("2026-08-18T00:00:01Z","100",5,5,"500",7));var duplicate=aggregator.accept(tick("2026-08-18T00:00:02Z","120",5,10,"1100",7)).getFirst();assertThat(duplicate.close()).isEqualByComparingTo("100");assertThat(duplicate.volume()).isEqualTo(5);}
 @Test void usesTradeVolumeWhenCumulativeVolumeResets(){aggregator.accept(tick("2026-08-18T00:00:01Z","100",5,100,"10000",1));var reset=aggregator.accept(tick("2026-08-18T00:00:02Z","101",3,2,"202",2)).getFirst();assertThat(reset.volume()).isEqualTo(8);}
 @Test void watermarkClosesSilentBucket(){aggregator.accept(tick("2026-08-18T00:00:01Z","100",1,1,"100",1));assertThat(aggregator.flush(Instant.parse("2026-08-18T00:05:01Z"))).isEmpty();assertThat(aggregator.flush(Instant.parse("2026-08-18T00:05:02Z"))).hasSize(1);}
 @Test void rejectsPreMarketTick(){assertThatThrownBy(()->aggregator.accept(tick("2026-08-17T23:59:59Z","100",1,1,"100",1))).isInstanceOf(IllegalArgumentException.class);}
 private MarketTick tick(String instant,String price,long volume,long cumulative,String value,long sequence){Instant time=Instant.parse(instant);return new MarketTick("005930",time.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),time,new BigDecimal(price),volume,cumulative,new BigDecimal(value),sequence);}
}
