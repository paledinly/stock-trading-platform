/*
 * ScannerEvaluator(스캐너 판정기)가 5분봉 기록으로 거래량 배수·등락률·점수를 계산하고,
 * 스캐너 종류별 조건 충족 여부를 맞게 판정하는지 확인해요.
 * 이 계산이 틀리면 스캐너 알림과 후보 평가가 모두 어긋나기 때문에 중요해요.
 */
package com.sunmo.stockplatform.scanner;
import com.sunmo.stockplatform.candle.application.CandleSnapshot;import com.sunmo.stockplatform.candle.domain.StockCandle;import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;import com.sunmo.stockplatform.scanner.application.ScannerEvaluator;import com.sunmo.stockplatform.scanner.domain.*;import org.junit.jupiter.api.Test;import java.math.BigDecimal;import java.time.*;import java.util.*;import static org.assertj.core.api.Assertions.*;
/** 스캐너 지표 계산과 조건 판정 규칙을 점검하는 테스트예요. */
class ScannerEvaluatorTest {
 private final ScannerEvaluator evaluator=new ScannerEvaluator();
 /**
  * 정상적인 지표 계산을 확인해요.
  * 준비: 거래량 10인 과거 5분봉 6개와, 거래량 30·종가 105인 현재 봉을 만들어요.
  * 기대: 준비 완료(ready) 상태이고 거래량 배수 3, 등락률 5%, 점수 8이 나와요.
  */
 @Test void calculatesGoldenMomentumMetrics(){List<StockCandle> previous=new ArrayList<>();for(int i=0;i<6;i++)previous.add(candle(Instant.parse("2026-08-18T00:25:00Z").minusSeconds(i*300L),"100",10));CandleSnapshot current=new CandleSnapshot("005930",Instant.parse("2026-08-18T00:30:00Z"),bd("100"),bd("105"),bd("99"),bd("105"),30,bd("3150"),true,0);var metrics=evaluator.calculate(current,previous);assertThat(metrics.ready()).isTrue();assertThat(metrics.volumeRatio()).isEqualByComparingTo("3.000000");assertThat(metrics.changeRate()).isEqualByComparingTo("5.000000");assertThat(metrics.score()).isEqualByComparingTo("8.000000");}
 /**
  * 과거 봉이 6개보다 적으면 계산하지 않는지 확인해요.
  * 준비: 과거 봉을 1개만 넘겨요.
  * 기대: ready가 false이고 사유가 INSUFFICIENT_HISTORY예요.
  */
 @Test void requiresSixHistoricalBuckets(){var metrics=evaluator.calculate(new CandleSnapshot("005930",Instant.now(),bd("100"),bd("100"),bd("100"),bd("100"),1,bd("100"),true,0),List.of(candle(Instant.now(),"100",1)));assertThat(metrics.ready()).isFalse();assertThat(metrics.reason()).isEqualTo("INSUFFICIENT_HISTORY");}
 /**
  * 기준 거래량이 0이면 배수를 계산하지 않는지 확인해요(0으로 나누기 방지).
  * 준비: 거래량 0인 과거 봉 6개와 거래량 10인 현재 봉을 만들어요.
  * 기대: 사유가 UNDEFINED_BASELINE이에요.
  */
 @Test void excludesZeroBaselineVolume(){Instant currentAt=Instant.parse("2026-08-18T00:30:00Z");List<StockCandle> previous=new ArrayList<>();for(int i=0;i<6;i++)previous.add(candle(currentAt.minusSeconds((i+1)*300L),"100",0));var metrics=evaluator.calculate(new CandleSnapshot("005930",currentAt,bd("100"),bd("100"),bd("100"),bd("100"),10,bd("1000"),true,0),previous);assertThat(metrics.reason()).isEqualTo("UNDEFINED_BASELINE");}
 /**
  * 거래량/거래대금/고가/VWAP 돌파·VWAP 회복 같은 확장 스캐너 유형이 지표와 시장 특성값으로 판정되는지 확인해요.
  * 준비: 거래량 3배·고가 106인 현재 봉과, 고정된 시장 특성 스냅샷(feature())을 준비해요.
  * 기대: 다섯 유형 모두 matched가 true예요.
  */
 @Test void detectsAdvancedScannerTypesFromMetricsAndFeatures(){List<StockCandle> previous=new ArrayList<>();for(int i=0;i<6;i++)previous.add(candle(Instant.parse("2026-08-18T00:25:00Z").minusSeconds(i*300L),"100",10));CandleSnapshot current=new CandleSnapshot("005930",Instant.parse("2026-08-18T00:30:00Z"),bd("100"),bd("106"),bd("99"),bd("105"),30,bd("3150"),true,0);var metrics=evaluator.calculate(current,previous);assertThat(evaluator.evaluate(setting(ScannerType.VOLUME_BREAKOUT,z(),bd("2")),metrics,current,bd("10000"),feature()).matched()).isTrue();assertThat(evaluator.evaluate(setting(ScannerType.TURNOVER_BREAKOUT,z(),bd("2")),metrics,current,bd("10000"),feature()).matched()).isTrue();assertThat(evaluator.evaluate(setting(ScannerType.HIGH_BREAKOUT,bd("1"),z()),metrics,current,bd("10000"),feature()).matched()).isTrue();assertThat(evaluator.evaluate(setting(ScannerType.VWAP_BREAKOUT,bd("1"),z()),metrics,current,bd("10000"),feature()).matched()).isTrue();assertThat(evaluator.evaluate(setting(ScannerType.VWAP_RECLAIM,bd("1"),z()),metrics,current,bd("10000"),feature()).matched()).isTrue();}
 /**
  * 조건을 만족했을 때 판정 사유가 정해진 JSON 구조로 남는지 확인해요.
  * 준비: VWAP_BREAKOUT 조건을 만족하는 현재 봉을 만들어요.
  * 기대: reasonJson에 버전(scanner-reason-v2), 유형, MATCHED 상태가 들어 있어요.
  */
 @Test void returnsStructuredReasonWhenMatched(){List<StockCandle> previous=new ArrayList<>();for(int i=0;i<6;i++)previous.add(candle(Instant.parse("2026-08-18T00:25:00Z").minusSeconds(i*300L),"100",10));CandleSnapshot current=new CandleSnapshot("005930",Instant.parse("2026-08-18T00:30:00Z"),bd("100"),bd("106"),bd("99"),bd("105"),30,bd("3150"),true,0);var decision=evaluator.evaluate(setting(ScannerType.VWAP_BREAKOUT,bd("1"),z()),evaluator.calculate(current,previous),current,bd("10000"),feature());assertThat(decision.reasonJson()).contains("\"version\":\"scanner-reason-v2\"");assertThat(decision.reasonJson()).contains("\"type\":\"VWAP_BREAKOUT\"");assertThat(decision.reasonJson()).contains("\"state\":\"MATCHED\"");}
 /** 시각·종가·거래량만 다른 테스트용 5분봉을 만들고, 문자열을 BigDecimal로 바꾸는 도우미예요. */
 private StockCandle candle(Instant at,String close,long volume){return new StockCandle(null,at,bd(close),bd(close),bd(close),bd(close),volume,bd("0"),true,0);}private BigDecimal bd(String v){return new BigDecimal(v);}
 /** BigDecimal 0을 짧게 쓰기 위한 도우미예요. */
 private BigDecimal z(){return BigDecimal.ZERO;}
 /** 유형·등락률·거래량 배수 기준만 바꾼 스캐너 설정을 만드는 도우미예요. */
 private ScannerSetting setting(ScannerType type,BigDecimal change,BigDecimal ratio){return new ScannerSetting(1L,type.name(),type,change,ratio,z(),z(),z(),false,300);}
 /** 판정에 쓰는 고정된 시장 특성 스냅샷(VWAP, 고저가 등)을 만드는 도우미예요. */
 private MarketFeatureSnapshot feature(){return new MarketFeatureSnapshot("005930",LocalDate.parse("2026-08-18"),Instant.parse("2026-08-18T00:30:01Z"),bd("105"),100,bd("10000"),bd("100"),bd("106"),bd("99"),bd("102"),bd("2.941176"),bd("0.100000"),bd("3.0"),bd("2.5"),bd("130"),20,5,bd("60"),bd("0.2"),false,z(),MarketFeatureSnapshot.VERSION);}
}
