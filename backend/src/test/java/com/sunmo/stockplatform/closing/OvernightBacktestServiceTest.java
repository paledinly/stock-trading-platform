/*
 * OvernightBacktestService(마감 추천 오버나이트 백테스트)를 확인하는 테스트예요.
 * 과거 Scanner 탐지로 가상 마감 추천을 만들고, 평가 이후 봉에서 진입가를 잡은 뒤 다음 거래일 봉으로 수익률을 계산하는지 봐요.
 * 진입 데이터나 다음 거래일 데이터가 없을 때 값을 지어내지 않는지도 함께 확인해서, 백테스트 결과가 부풀려지지 않도록 지켜줘요.
 */
package com.sunmo.stockplatform.closing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.application.BacktestIntegrityService;
import com.sunmo.stockplatform.closing.application.ClosingRecommendationScorer;
import com.sunmo.stockplatform.closing.application.ClosingPrecisionEvaluator;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.closing.application.DailyMovingAverageFeature;
import com.sunmo.stockplatform.closing.application.DailyMovingAverageService;
import com.sunmo.stockplatform.closing.application.IntradayMovingAverageFeature;
import com.sunmo.stockplatform.closing.application.IntradayMovingAverageService;
import com.sunmo.stockplatform.closing.application.OvernightBacktestService;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import com.sunmo.stockplatform.scanner.domain.ScannerType;
import com.sunmo.stockplatform.scanner.infrastructure.ScannerDetectionRepository;
import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 채점관처럼 과거 탐지를 가상으로 사고팔아 본 결과(수익률, 요약, 무결성 경고)가 정확히 계산되는지 확인해요.
 */
class OvernightBacktestServiceTest {
    // 저장소와 이동평균 서비스는 목(mock, 가짜 객체)으로 두고, 점수 계산기와 무결성 검사는 실제 객체를 써요.
    private final ScannerDetectionRepository detections = mock(ScannerDetectionRepository.class);
    private final StockCandleRepository candles = mock(StockCandleRepository.class);
    private final IntradayMovingAverageService intradayMa = mock(IntradayMovingAverageService.class);
    private final DailyMovingAverageService dailyMa = mock(DailyMovingAverageService.class);
    private final ClosingPrecisionEvaluator precisionEvaluator = mock(ClosingPrecisionEvaluator.class);
    private final OvernightBacktestService service = new OvernightBacktestService(detections, candles,
            new ClosingRecommendationScorer(new ObjectMapper().findAndRegisterModules()), new BacktestIntegrityService(),
            intradayMa, dailyMa, precisionEvaluator, new ClosingRecommendationProperties(20, 4, bd("55"),
                    // 평가 시작 14:30, 평가 기준 15:00, 진입 마감 15:20, 진입 지연 허용 10초로 보여요(운영 v9.1의 15:15와 다른 값이에요).
                    java.time.LocalTime.of(14, 30), java.time.LocalTime.of(15, 0),
                    java.time.LocalTime.of(15, 20), java.time.Duration.ofSeconds(10)), calendar(),
            new com.sunmo.stockplatform.closing.application.OvernightExecutionSimulator(
                    new com.sunmo.stockplatform.closing.config.TradingCostProperties(
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));

    /**
     * 정밀 평가기 목이 들어온 탐지를 모두 조건 통과(QUALIFIED)로 돌려주도록 설정해요.
     */
    OvernightBacktestServiceTest() {
        ClosingRecommendationScorer scorer = new ClosingRecommendationScorer(new ObjectMapper().findAndRegisterModules());
        org.mockito.stubbing.Answer<List<ClosingPrecisionEvaluator.Assessment>> accepted = invocation -> {
            List<ScannerDetection> source = invocation.getArgument(0);
            return source.stream().map(row -> new ClosingPrecisionEvaluator.Assessment(row,
                    scorer.score(row), 4, 20, List.of(), "QUALIFIED", java.util.Map.of("dailyCandles", 60))).toList();
        };
        when(precisionEvaluator.ranked(anyList(), any(), any(), any(), any(), anyInt())).thenAnswer(accepted);
        when(precisionEvaluator.representatives(anyList(), any(), any(), any(), any())).thenAnswer(accepted);
        when(precisionEvaluator.representativesWithoutMovingAverage(anyList(), any(), any(), any(), any()))
                .thenAnswer(accepted);
    }

    /**
     * 가상 마감 추천 1건의 다음 거래일 수익률과 요약 통계가 맞게 계산되는지 확인해요.
     * 입력: 15:05 진입봉(종가 100)과 빠짐없는 다음 거래일 5분봉(09:00 시가 103, 09:05 최고 106·최저 98, 나머지 103,
     * 15:25 종가 봉 104), 목표 +3%, 손절 -2%.
     * 기대: 시가 수익 3%, 종가 수익 4%, 최대 6%, 최대 낙폭 -2%, 샘플 부족 경고, TARGET_OR_STOP은 먼저 닿은 목표로 3%예요.
     */
    @Test
    void calculatesOvernightReturnsFromVirtualClosingRecommendations() {
        // 준비
        ScannerDetection detection = detection();
        when(detections.findByDetectedAtBetweenOrderByDetectedAtAsc(any(), any())).thenReturn(List.of(detection));
        when(intradayMa.calculate(detection)).thenReturn(IntradayMovingAverageFeature.empty(0));
        when(dailyMa.calculate(detection)).thenReturn(DailyMovingAverageFeature.empty(0));
        stubCandles(List.of(candle("2026-09-04T06:05:00Z", "100", "101", "99", "100")), fullSession(
                candle("2026-09-07T00:00:00Z", "103", "104", "102", "103"),
                candle("2026-09-07T00:05:00Z", "103", "106", "98", "104"), "103", "104"));

        // 실행
        var result = service.run(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 4),
                10, bd("35"), bd("65"), bd("3"), bd("-2"));

        // 확인(어서션, 기대값 검사)
        assertThat(result.tradingDays()).isEqualTo(1);
        assertThat(result.virtualRecommendations()).isEqualTo(1);
        assertThat(result.completed()).isEqualTo(1);
        assertThat(result.dataMissing()).isZero();
        assertThat(result.winRateOpen()).isEqualByComparingTo("100.000000");
        assertThat(result.averageOpenReturn()).isEqualByComparingTo("3.000000");
        assertThat(result.averageCloseReturn()).isEqualByComparingTo("4.000000");
        assertThat(result.averageMaxReturn()).isEqualByComparingTo("6.000000");
        assertThat(result.averageMaxDrawdown()).isEqualByComparingTo("-2.000000");
        assertThat(result.rows().getFirst().targetHit()).isTrue();
        assertThat(result.rows().getFirst().stopHit()).isTrue();
        assertThat(result.integrity().status()).isEqualTo("WARNING");
        assertThat(result.integrity().issues()).extracting("message")
                .contains("백테스트 샘플 부족", "목표/손절 동시 도달");
        assertThat(result.strategySummaries()).extracting("strategy", "sampleSize")
                .contains(tuple("NEXT_OPEN", 1), tuple("TARGET_OR_STOP", 1), tuple("EXTEND_WHILE_HEALTHY", 1));
        assertThat(result.algorithmSummaries()).extracting("algorithm", "sampleSize", "completed", "confidence")
                .contains(
                        tuple("SCANNER_BASELINE", 1, 1, "LOW"),
                        tuple("CLOSING_NO_MA_THRESHOLD_55", 1, 1, "LOW"),
                        tuple("CLOSING_MA", 1, 1, "LOW"),
                        tuple("CLOSING_MA_STRICT", 1, 1, "LOW"));
        assertThat(result.algorithmSummaries()).filteredOn("recommendedDefault", true).hasSize(1);
        assertThat(result.strategySummaries()).filteredOn(summary -> summary.strategy().equals("TARGET_OR_STOP"))
                .first()
                .satisfies(summary -> {
                    assertThat(summary.averageReturnRate()).isEqualByComparingTo("3.000000");
                    // 비용이 모두 0이라 Net 수익률은 현실 수익으로 보지 않고 비워 두는 것으로 보여요.
                    assertThat(summary.averageNetReturnRate()).isNull();
                    assertThat(summary.ambiguousCount()).isZero();
                });
    }

    /**
     * 한 봉 안에서 목표와 손절이 모두 닿으면 보수적으로 손절로 처리하는지 확인해요.
     * 입력: 빠짐없는 다음 거래일 5분봉 중 첫 봉 최고 104(목표 +3% 도달), 최저 98(손절 -2% 도달).
     * 기대: TARGET_OR_STOP 평균 수익 -2%, 모호한(ambiguous) 건수 1, 손절률 100%예요.
     */
    @Test
    void treatsSameCandleTargetAndStopAsConservativeStopInExitStrategy() {
        ScannerDetection detection = detection();
        when(detections.findByDetectedAtBetweenOrderByDetectedAtAsc(any(), any())).thenReturn(List.of(detection));
        when(intradayMa.calculate(detection)).thenReturn(IntradayMovingAverageFeature.empty(0));
        when(dailyMa.calculate(detection)).thenReturn(DailyMovingAverageFeature.empty(0));
        stubCandles(List.of(candle("2026-09-04T06:05:00Z", "100", "101", "99", "100")),
                fullSession(candle("2026-09-07T00:00:00Z", "100", "104", "98", "101"),
                        candle("2026-09-07T00:05:00Z", "101", "101", "101", "101"), "101", "101"));

        var result = service.run(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 4),
                10, bd("35"), bd("65"), bd("3"), bd("-2"));

        assertThat(result.strategySummaries()).filteredOn(summary -> summary.strategy().equals("TARGET_OR_STOP"))
                .first()
                .satisfies(summary -> {
                    assertThat(summary.averageReturnRate()).isEqualByComparingTo("-2.000000");
                    assertThat(summary.ambiguousCount()).isEqualTo(1);
                    assertThat(summary.stopHitRate()).isEqualByComparingTo("100.000000");
                });
    }

    /**
     * 다음 거래일 봉이 없으면 완료로 세지 않고 데이터 누락으로 보고하는지 확인해요.
     * 입력: 진입봉(시가 101)은 있고 다음 거래일 봉은 빈 목록이에요.
     * 기대: 완료 0, 누락 1, 신호가 100과 진입 기준가 101은 남고, "다음 거래일 데이터 없음" 경고가 나와요.
     */
    @Test
    void reportsMissingNextSessionData() {
        ScannerDetection detection = detection();
        when(detections.findByDetectedAtBetweenOrderByDetectedAtAsc(any(), any())).thenReturn(List.of(detection));
        when(intradayMa.calculate(detection)).thenReturn(IntradayMovingAverageFeature.empty(0));
        when(dailyMa.calculate(detection)).thenReturn(DailyMovingAverageFeature.empty(0));
        stubCandles(List.of(candle("2026-09-04T06:05:00Z", "101", "102", "100", "101")), List.of());

        var result = service.run(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 4),
                10, bd("35"), bd("65"), bd("3"), bd("-2"));

        assertThat(result.completed()).isZero();
        assertThat(result.dataMissing()).isEqualTo(1);
        assertThat(result.rows().getFirst().signalPrice()).isEqualByComparingTo("100");
        assertThat(result.rows().getFirst().buyReferencePrice()).isEqualByComparingTo("101");
        assertThat(result.rows().getFirst().entryAt()).isEqualTo(Instant.parse("2026-09-04T06:05:00Z"));
        assertThat(result.strategySummaries()).allSatisfy(summary -> assertThat(summary.sampleSize()).isZero());
        assertThat(result.algorithmSummaries()).allSatisfy(summary -> {
            assertThat(summary.completed()).isZero();
            assertThat(summary.confidence()).isEqualTo("LOW");
        });
        assertThat(result.integrity().status()).isEqualTo("WARNING");
        assertThat(result.integrity().issues()).extracting("category", "message")
                .contains(tuple("DATA_COVERAGE", "다음 거래일 데이터 없음"));
    }

    /**
     * 평가 이후 진입봉이 없으면 진입가를 지어내지 않는지 확인해요.
     * 입력: 당일 진입봉은 비어 있고 다음 거래일 봉만 있어요.
     * 기대: 상태 ENTRY_DATA_MISSING, 진입 기준가 null, "평가 이후 진입 데이터 없음" 경고예요.
     */
    @Test
    void doesNotInventAnEntryWhenThePostEvaluationCandleIsMissing() {
        ScannerDetection detection = detection();
        when(detections.findByDetectedAtBetweenOrderByDetectedAtAsc(any(), any())).thenReturn(List.of(detection));
        when(intradayMa.calculate(detection)).thenReturn(IntradayMovingAverageFeature.empty(0));
        when(dailyMa.calculate(detection)).thenReturn(DailyMovingAverageFeature.empty(0));
        stubCandles(List.of(), List.of(candle("2026-09-07T00:00:00Z", "103", "104", "102", "103")));

        var result = service.run(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 4),
                10, bd("35"), bd("65"), bd("3"), bd("-2"));

        assertThat(result.rows().getFirst().status()).isEqualTo("ENTRY_DATA_MISSING");
        assertThat(result.rows().getFirst().buyReferencePrice()).isNull();
        assertThat(result.integrity().issues()).extracting("message").contains("평가 이후 진입 데이터 없음");
    }

    /**
     * 다음 거래일 봉이 일부만 있으면 완료로 세지 않고 수익률도 만들지 않는지 확인해요(성과 추적 서비스와 같은 완전성 규칙).
     * 입력: 진입봉은 있고 다음 거래일 봉은 09:00·09:05 두 개뿐이에요(15:25 종가 봉 없음).
     * 기대: 상태 NEXT_SESSION_DATA_INCOMPLETE, 완료 0·누락 1, 종가 수익률 null, 청산 방식 표본 0이에요.
     */
    @Test
    void partialNextSessionIsNotCompleted() {
        ScannerDetection detection = detection();
        when(detections.findByDetectedAtBetweenOrderByDetectedAtAsc(any(), any())).thenReturn(List.of(detection));
        when(intradayMa.calculate(detection)).thenReturn(IntradayMovingAverageFeature.empty(0));
        when(dailyMa.calculate(detection)).thenReturn(DailyMovingAverageFeature.empty(0));
        stubCandles(List.of(candle("2026-09-04T06:05:00Z", "100", "101", "99", "100")), List.of(
                candle("2026-09-07T00:00:00Z", "103", "104", "102", "103"),
                candle("2026-09-07T00:05:00Z", "103", "106", "98", "104")));

        var result = service.run(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 4),
                10, bd("35"), bd("65"), bd("3"), bd("-2"));

        assertThat(result.completed()).isZero();
        assertThat(result.dataMissing()).isEqualTo(1);
        assertThat(result.rows().getFirst().status()).isEqualTo("NEXT_SESSION_DATA_INCOMPLETE");
        assertThat(result.rows().getFirst().closeReturnRate()).isNull();
        assertThat(result.strategySummaries()).allSatisfy(summary -> assertThat(summary.sampleSize()).isZero());
    }

    /**
     * 가격 정합이 깨진 봉은 빼고 완전성을 따지는지 확인해요.
     * 입력: 빠짐없는 다음 거래일 봉이지만 10:00 봉의 고가가 종가보다 낮아요(정합 위반).
     * 기대: 그 봉이 빠진 것으로 보여 NEXT_SESSION_DATA_INCOMPLETE이고 완료 0이에요.
     */
    @Test
    void priceInconsistentNextSessionBarMakesTheSessionIncomplete() {
        ScannerDetection detection = detection();
        when(detections.findByDetectedAtBetweenOrderByDetectedAtAsc(any(), any())).thenReturn(List.of(detection));
        when(intradayMa.calculate(detection)).thenReturn(IntradayMovingAverageFeature.empty(0));
        when(dailyMa.calculate(detection)).thenReturn(DailyMovingAverageFeature.empty(0));
        List<StockCandle> session = new java.util.ArrayList<>(fullSession(
                candle("2026-09-07T00:00:00Z", "103", "104", "102", "103"),
                candle("2026-09-07T00:05:00Z", "103", "106", "98", "104"), "103", "104"));
        session.replaceAll(row -> row.getStartTime().equals(Instant.parse("2026-09-07T01:00:00Z"))
                ? candle("2026-09-07T01:00:00Z", "103", "102", "101", "103") : row);
        stubCandles(List.of(candle("2026-09-04T06:05:00Z", "100", "101", "99", "100")), session);

        var result = service.run(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 4),
                10, bd("35"), bd("65"), bd("3"), bd("-2"));

        assertThat(result.completed()).isZero();
        assertThat(result.rows().getFirst().status()).isEqualTo("NEXT_SESSION_DATA_INCOMPLETE");
    }

    /** 2026-09-04 14:55(KST)에 삼성전자 VWAP 돌파로 탐지된 가짜 Scanner 탐지를 만들어요. */
    private ScannerDetection detection() {
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(1L);
        when(stock.getStockCode()).thenReturn("005930");
        when(stock.getStockName()).thenReturn("삼성전자");
        when(stock.getMarket()).thenReturn(Market.KOSPI);
        when(stock.isActive()).thenReturn(true);

        ScannerDetection detection = mock(ScannerDetection.class);
        when(detection.getStock()).thenReturn(stock);
        when(detection.getType()).thenReturn(ScannerType.VWAP_BREAKOUT);
        when(detection.getSessionDate()).thenReturn(LocalDate.of(2026, 9, 4));
        when(detection.getDetectedAt()).thenReturn(Instant.parse("2026-09-04T05:55:00Z"));
        when(detection.getDetectedPrice()).thenReturn(bd("100"));
        when(detection.getOpportunityScore()).thenReturn(bd("70"));
        when(detection.getRiskScore()).thenReturn(bd("20"));
        when(detection.getVolumeRatio()).thenReturn(bd("2.5"));
        when(detection.getDailyValue()).thenReturn(bd("10000000000"));
        when(detection.getChangeRate()).thenReturn(bd("1.5"));
        when(detection.getScore()).thenReturn(bd("5"));
        when(detection.getFeatureSnapshot()).thenReturn(
                "{\"vwapDistanceRate\":1.2,\"dayHighDistanceRate\":0.5,\"tradeStrength\":130}");
        return detection;
    }

    /** 주어진 시작시각(UTC)과 OHLC 가격으로 확정된 봉을 만들어요. */
    private StockCandle candle(String at, String open, String high, String low, String close) {
        return new StockCandle(null, Instant.parse(at), bd(open), bd(high), bd(low), bd(close),
                100, bd("100000"), true, 0);
    }

    /**
     * 2026-09-07(월) 다음 거래일의 빠짐없는 5분봉을 만들어요: 09:00·09:05는 주어진 봉, 09:10~15:15는 fill 가격의 평평한 봉,
     * 15:20 동시호가 봉은 없고(선택), 15:25 종가 봉은 closePrint 가격이에요.
     */
    private List<StockCandle> fullSession(StockCandle first, StockCandle second, String fill, String closePrint) {
        List<StockCandle> rows = new java.util.ArrayList<>(List.of(first, second));
        Instant callStart = Instant.parse("2026-09-07T06:20:00Z");
        for (Instant at = Instant.parse("2026-09-07T00:10:00Z"); at.isBefore(callStart); at = at.plusSeconds(300))
            rows.add(candle(at.toString(), fill, fill, fill, fill));
        rows.add(candle("2026-09-07T06:25:00Z", closePrint, closePrint, closePrint, closePrint));
        return rows;
    }

    /** 조회 시작일이 2026-09-04(KST)면 진입봉 목록을, 그 외 날짜면 다음 거래일 봉 목록을 돌려주도록 목을 설정해요. */
    private void stubCandles(List<StockCandle> entry, List<StockCandle> nextSession) {
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("5M"), any(), any())).thenAnswer(invocation -> {
                    Instant from = invocation.getArgument(2);
                    LocalDate date = from.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
                    return date.equals(LocalDate.of(2026, 9, 4)) ? entry : nextSession;
                });
    }

    /** 추가 휴장일 없이 주말만 쉬는 기본 거래일 달력을 만들어요. */
    private ClosingTradingCalendar calendar() {
        return new ClosingTradingCalendar(new com.sunmo.stockplatform.market.config.MarketWideScheduleProperties(
                false, 0, 0, false, null, null, null, null, null, List.of()));
    }

    /** 문자열을 BigDecimal로 바꾸는 짧은 도우미예요. */
    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
