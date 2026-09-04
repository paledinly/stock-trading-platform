package com.sunmo.stockplatform.closing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.application.BacktestIntegrityService;
import com.sunmo.stockplatform.closing.application.ClosingRecommendationScorer;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OvernightBacktestServiceTest {
    private final ScannerDetectionRepository detections = mock(ScannerDetectionRepository.class);
    private final StockCandleRepository candles = mock(StockCandleRepository.class);
    private final IntradayMovingAverageService intradayMa = mock(IntradayMovingAverageService.class);
    private final DailyMovingAverageService dailyMa = mock(DailyMovingAverageService.class);
    private final OvernightBacktestService service = new OvernightBacktestService(detections, candles,
            new ClosingRecommendationScorer(new ObjectMapper().findAndRegisterModules()), new BacktestIntegrityService(),
            intradayMa, dailyMa);

    @Test
    void calculatesOvernightReturnsFromVirtualClosingRecommendations() {
        ScannerDetection detection = detection();
        when(detections.findByDetectedAtBetweenOrderByDetectedAtAsc(any(), any())).thenReturn(List.of(detection));
        when(intradayMa.calculate(detection)).thenReturn(IntradayMovingAverageFeature.empty(0));
        when(dailyMa.calculate(detection)).thenReturn(DailyMovingAverageFeature.empty(0));
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("5M"), any(), any()))
                .thenReturn(List.of(
                        candle("2026-09-05T00:00:00Z", "103", "104", "102", "103"),
                        candle("2026-09-05T00:05:00Z", "103", "106", "98", "104")));

        var result = service.run(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 4),
                10, bd("35"), bd("65"), bd("3"), bd("-2"));

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
                        tuple("CLOSING_NO_MA", 1, 1, "LOW"),
                        tuple("CLOSING_MA", 1, 1, "LOW"),
                        tuple("CLOSING_MA_STRICT", 1, 1, "LOW"));
        assertThat(result.algorithmSummaries()).filteredOn("recommendedDefault", true).hasSize(1);
        assertThat(result.strategySummaries()).filteredOn(summary -> summary.strategy().equals("TARGET_OR_STOP"))
                .first()
                .satisfies(summary -> {
                    assertThat(summary.averageReturnRate()).isEqualByComparingTo("3.000000");
                    assertThat(summary.ambiguousCount()).isZero();
                });
    }

    @Test
    void treatsSameCandleTargetAndStopAsConservativeStopInExitStrategy() {
        ScannerDetection detection = detection();
        when(detections.findByDetectedAtBetweenOrderByDetectedAtAsc(any(), any())).thenReturn(List.of(detection));
        when(intradayMa.calculate(detection)).thenReturn(IntradayMovingAverageFeature.empty(0));
        when(dailyMa.calculate(detection)).thenReturn(DailyMovingAverageFeature.empty(0));
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("5M"), any(), any()))
                .thenReturn(List.of(candle("2026-09-05T00:00:00Z", "100", "104", "98", "101")));

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

    @Test
    void reportsMissingNextSessionData() {
        ScannerDetection detection = detection();
        when(detections.findByDetectedAtBetweenOrderByDetectedAtAsc(any(), any())).thenReturn(List.of(detection));
        when(intradayMa.calculate(detection)).thenReturn(IntradayMovingAverageFeature.empty(0));
        when(dailyMa.calculate(detection)).thenReturn(DailyMovingAverageFeature.empty(0));
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("5M"), any(), any())).thenReturn(List.of());

        var result = service.run(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 4),
                10, bd("35"), bd("65"), bd("3"), bd("-2"));

        assertThat(result.completed()).isZero();
        assertThat(result.dataMissing()).isEqualTo(1);
        assertThat(result.strategySummaries()).allSatisfy(summary -> assertThat(summary.sampleSize()).isZero());
        assertThat(result.algorithmSummaries()).allSatisfy(summary -> {
            assertThat(summary.completed()).isZero();
            assertThat(summary.confidence()).isEqualTo("LOW");
        });
        assertThat(result.integrity().status()).isEqualTo("WARNING");
        assertThat(result.integrity().issues()).extracting("category", "message")
                .contains(tuple("DATA_COVERAGE", "다음 거래일 데이터 없음"));
    }

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
        when(detection.getDetectedAt()).thenReturn(Instant.parse("2026-09-04T06:05:00Z"));
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

    private StockCandle candle(String at, String open, String high, String low, String close) {
        return new StockCandle(null, Instant.parse(at), bd(open), bd(high), bd(low), bd(close),
                100, bd("100000"), true, 0);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
