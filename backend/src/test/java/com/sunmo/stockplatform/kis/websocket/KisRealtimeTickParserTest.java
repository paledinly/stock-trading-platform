/*
 * KisRealtimeTickParser가 KIS(한국투자증권) 실시간 체결 메시지('^'로 구분된 필드 문자열)를
 * 틱 객체로 올바르게 바꾸는지 확인해요.
 * 필드 위치가 하나만 밀려도 가격·거래량이 엉뚱하게 저장되므로 중요해요.
 */
package com.sunmo.stockplatform.kis.websocket;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
/** 실시간 체결 문자열 파싱 규칙을 점검하는 테스트예요. */
class KisRealtimeTickParserTest {
 /**
  * 한 메시지에 47필드 체결 레코드가 여러 건 붙어 와도 레코드가 밀리지 않는지 확인해요.
  * 준비: 종목만 다른 47필드 레코드 2개를 이어 붙여 건수 2로 파싱해요.
  * 기대: 종목코드가 순서대로 나오고 두 번째 누적거래량이 110이에요.
  */
 @Test void handlesMultipleCurrent47FieldTradesWithoutShiftingRecords(){
  String[] first=new String[47];java.util.Arrays.fill(first,"0");
  first[0]="005930";first[1]="143000";first[2]="72000";first[12]="10";
  first[13]="100";first[33]="20261002";first[46]="J";
  String[] second=first.clone();second[0]="000660";second[13]="110";
  var ticks=new KisRealtimeTickParser().parseMany(String.join("^",first)+"^"+String.join("^",second),2);
  assertThat(ticks).extracting(t->t.stockCode()).containsExactly("005930","000660");
  assertThat(ticks.get(1).cumulativeVolume()).isEqualTo(110);
 }
 /**
  * 공식 문서의 필드 위치대로 값이 매핑되는지 확인해요.
  * 준비: 46필드 배열에 종목·시각·가격·체결강도·거래정지 등 값을 채워요.
  * 기대: 각 값이 대응하는 틱 속성으로 들어가고, 09:15:01(서울)은 UTC 00:15:01로 바뀌어요.
  */
 @Test void mapsOfficialRealtimeTradeFieldPositions(){String[] fields=new String[46];java.util.Arrays.fill(fields,"0");fields[0]="005930";fields[1]="091501";fields[2]="72000";fields[7]="71000";fields[8]="73000";fields[9]="70000";fields[12]="15";fields[13]="123456";fields[14]="8888832000";fields[18]="132.45";fields[19]="45678";fields[20]="54321";fields[22]="54.3";fields[33]="20260923";fields[35]="Y";fields[40]="0.82";fields[45]="75000";var tick=new KisRealtimeTickParser().parse(String.join("^",fields));assertThat(tick.stockCode()).isEqualTo("005930");assertThat(tick.businessDate()).isEqualTo(java.time.LocalDate.of(2026,9,23));assertThat(tick.occurredAt()).isEqualTo(java.time.Instant.parse("2026-09-23T00:15:01Z"));assertThat(tick.price()).isEqualByComparingTo("72000");assertThat(tick.tradeVolume()).isEqualTo(15);assertThat(tick.cumulativeVolume()).isEqualTo(123456);assertThat(tick.openPrice()).isEqualByComparingTo("71000");assertThat(tick.highPrice()).isEqualByComparingTo("73000");assertThat(tick.lowPrice()).isEqualByComparingTo("70000");assertThat(tick.tradeStrength()).isEqualByComparingTo("132.45");assertThat(tick.cumulativeSellVolume()).isEqualTo(45678);assertThat(tick.cumulativeBuyVolume()).isEqualTo(54321);assertThat(tick.buyRatio()).isEqualByComparingTo("54.3");assertThat(tick.tradingHalted()).isTrue();assertThat(tick.viStandardPrice()).isEqualByComparingTo("75000");assertThat(tick.turnoverRate()).isEqualByComparingTo("0.82");}

 /**
  * 필수 숫자(가격)가 비어 있으면 0으로 대충 넣지 않고 오류를 내는지 확인해요.
  * 준비: 가격 필드를 빈 문자열로 둬요.
  * 기대: "price is blank" 메시지의 예외가 나요.
  */
 @Test void rejectsBlankRequiredNumericInsteadOfSilentlyUsingZero(){String[] fields=new String[46];java.util.Arrays.fill(fields,"0");fields[0]="005930";fields[1]="091501";fields[2]="";fields[12]="15";fields[13]="123456";fields[14]="8888832000";fields[33]="20260923";assertThatThrownBy(()->new KisRealtimeTickParser().parse(String.join("^",fields))).hasMessageContaining("price is blank");}
}
