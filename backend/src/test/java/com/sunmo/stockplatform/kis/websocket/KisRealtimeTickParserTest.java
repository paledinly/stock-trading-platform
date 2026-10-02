package com.sunmo.stockplatform.kis.websocket;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class KisRealtimeTickParserTest {
 @Test void handlesMultipleCurrent47FieldTradesWithoutShiftingRecords(){
  String[] first=new String[47];java.util.Arrays.fill(first,"0");
  first[0]="005930";first[1]="143000";first[2]="72000";first[12]="10";
  first[13]="100";first[33]="20261002";first[46]="J";
  String[] second=first.clone();second[0]="000660";second[13]="110";
  var ticks=new KisRealtimeTickParser().parseMany(String.join("^",first)+"^"+String.join("^",second),2);
  assertThat(ticks).extracting(t->t.stockCode()).containsExactly("005930","000660");
  assertThat(ticks.get(1).cumulativeVolume()).isEqualTo(110);
 }
 @Test void mapsOfficialRealtimeTradeFieldPositions(){String[] fields=new String[46];java.util.Arrays.fill(fields,"0");fields[0]="005930";fields[1]="091501";fields[2]="72000";fields[7]="71000";fields[8]="73000";fields[9]="70000";fields[12]="15";fields[13]="123456";fields[14]="8888832000";fields[18]="132.45";fields[19]="45678";fields[20]="54321";fields[22]="54.3";fields[33]="20260923";fields[35]="Y";fields[40]="0.82";fields[45]="75000";var tick=new KisRealtimeTickParser().parse(String.join("^",fields));assertThat(tick.stockCode()).isEqualTo("005930");assertThat(tick.businessDate()).isEqualTo(java.time.LocalDate.of(2026,9,23));assertThat(tick.occurredAt()).isEqualTo(java.time.Instant.parse("2026-09-23T00:15:01Z"));assertThat(tick.price()).isEqualByComparingTo("72000");assertThat(tick.tradeVolume()).isEqualTo(15);assertThat(tick.cumulativeVolume()).isEqualTo(123456);assertThat(tick.openPrice()).isEqualByComparingTo("71000");assertThat(tick.highPrice()).isEqualByComparingTo("73000");assertThat(tick.lowPrice()).isEqualByComparingTo("70000");assertThat(tick.tradeStrength()).isEqualByComparingTo("132.45");assertThat(tick.cumulativeSellVolume()).isEqualTo(45678);assertThat(tick.cumulativeBuyVolume()).isEqualTo(54321);assertThat(tick.buyRatio()).isEqualByComparingTo("54.3");assertThat(tick.tradingHalted()).isTrue();assertThat(tick.viStandardPrice()).isEqualByComparingTo("75000");assertThat(tick.turnoverRate()).isEqualByComparingTo("0.82");}

 @Test void rejectsBlankRequiredNumericInsteadOfSilentlyUsingZero(){String[] fields=new String[46];java.util.Arrays.fill(fields,"0");fields[0]="005930";fields[1]="091501";fields[2]="";fields[12]="15";fields[13]="123456";fields[14]="8888832000";fields[33]="20260923";assertThatThrownBy(()->new KisRealtimeTickParser().parse(String.join("^",fields))).hasMessageContaining("price is blank");}
}
