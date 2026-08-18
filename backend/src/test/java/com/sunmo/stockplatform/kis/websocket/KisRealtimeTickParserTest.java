package com.sunmo.stockplatform.kis.websocket;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class KisRealtimeTickParserTest {
 @Test void mapsOfficialRealtimeTradeFieldPositions(){String[] fields=new String[46];java.util.Arrays.fill(fields,"0");fields[0]="005930";fields[1]="091501";fields[2]="72000";fields[12]="15";fields[13]="123456";fields[14]="8888832000";var tick=new KisRealtimeTickParser().parse(String.join("^",fields));assertThat(tick.stockCode()).isEqualTo("005930");assertThat(tick.price()).isEqualByComparingTo("72000");assertThat(tick.tradeVolume()).isEqualTo(15);assertThat(tick.cumulativeVolume()).isEqualTo(123456);}
}
