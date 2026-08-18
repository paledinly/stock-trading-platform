package com.sunmo.stockplatform.candle;
import com.sunmo.stockplatform.candle.application.FiveMinuteCandleAggregator;
import com.sunmo.stockplatform.market.domain.MarketTick;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class LateTickRevisionTest {
 @Test void revisesImmediatelyPreviousBucket(){var aggregator=new FiveMinuteCandleAggregator(Duration.ofSeconds(2));aggregator.accept(tick("2026-08-18T00:00:01Z","100",1,1,1));aggregator.accept(tick("2026-08-18T00:05:01Z","110",1,2,2));var revised=aggregator.accept(tick("2026-08-18T00:04:59Z","120",2,3,3)).getFirst();assertThat(revised.finalCandle()).isTrue();assertThat(revised.high()).isEqualByComparingTo("120");assertThat(revised.close()).isEqualByComparingTo("120");assertThat(revised.revision()).isEqualTo(1);}
 private MarketTick tick(String value,String price,long volume,long cumulative,long sequence){Instant instant=Instant.parse(value);return new MarketTick("005930",instant.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),instant,new BigDecimal(price),volume,cumulative,new BigDecimal(price).multiply(BigDecimal.valueOf(cumulative)),sequence);}
}
