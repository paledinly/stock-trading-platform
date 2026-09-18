package com.sunmo.stockplatform.closing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.application.*;
import com.sunmo.stockplatform.closing.application.ClosingRecommendationScorer.ScoreResult;
import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClosingPrecisionEvaluatorTest {
    private static final Instant AS_OF = Instant.parse("2026-09-16T06:20:00Z");
    private static final Instant FROM = Instant.parse("2026-09-16T05:30:00Z");
    private final StockCandleRepository candles = mock(StockCandleRepository.class);
    private final IntradayMovingAverageService intraday = mock(IntradayMovingAverageService.class);
    private final DailyMovingAverageService daily = mock(DailyMovingAverageService.class);
    private final ClosingRecommendationScorer scorer = mock(ClosingRecommendationScorer.class);
    private final ClosingPrecisionEvaluator evaluator = new ClosingPrecisionEvaluator(scorer, intraday, daily,
            candles, new ClosingRecommendationProperties(20, 4, new BigDecimal("55"),
                    LocalTime.of(14, 30), LocalTime.of(15, 20)), new ObjectMapper(), new ClosingTradingCalendar(
                            new com.sunmo.stockplatform.market.config.MarketWideScheduleProperties(false, 0, 0,
                                    false, null, null, null, null, null, List.of())));

    @Test
    void sameAssessmentControlsRankingAndExcludesLateCandleRevision() {
        ScannerDetection signal = signal(AS_OF);
        DailyMovingAverageFeature ready = dailyReady();
        when(daily.calculate(signal)).thenReturn(ready);
        when(scorer.score(eq(signal), any(), any())).thenReturn(new ScoreResult(new BigDecimal("70"), "{}", "{}"));
        List<StockCandle> rows = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> candle(AS_OF.minus(Duration.ofMinutes(20 - index * 5L)), AS_OF.minusSeconds(1)))
                .toList();
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("5M"), eq(FROM), any())).thenReturn(rows);

        var accepted = evaluator.assess(signal, FROM, AS_OF, BigDecimal.ZERO, new BigDecimal("100"));
        assertThat(accepted.reason()).isEqualTo("QUALIFIED");
        assertThat(evaluator.ranked(List.of(signal), FROM, AS_OF, BigDecimal.ZERO, new BigDecimal("100"), 10))
                .extracting(ClosingPrecisionEvaluator.Assessment::detection).containsExactly(signal);

        StockCandle revisedAfterCutoff = candle(AS_OF.minus(Duration.ofMinutes(5)), AS_OF.plusSeconds(1));
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("5M"), eq(FROM), any())).thenReturn(List.of(rows.get(0), rows.get(1), rows.get(2), revisedAfterCutoff));
        var excluded = evaluator.assess(signal, FROM, AS_OF, BigDecimal.ZERO, new BigDecimal("100"));
        assertThat(excluded.reason()).isEqualTo("RECEIVED_AFTER_EVALUATION");
        assertThat(evaluator.ranked(List.of(signal), FROM, AS_OF, BigDecimal.ZERO, new BigDecimal("100"), 10)).isEmpty();
    }

    @Test
    void missingDailyHistoryAndLatestRejectedSignalCannotReuseOlderSignal() {
        ScannerDetection old = signal(AS_OF.minus(Duration.ofMinutes(10)));
        ScannerDetection latest = signal(AS_OF);
        when(daily.calculate(latest)).thenReturn(DailyMovingAverageFeature.empty(0));
        when(scorer.score(eq(latest), any(), any())).thenReturn(new ScoreResult(new BigDecimal("80"), "{}", "{}"));

        var representatives = evaluator.representatives(List.of(old, latest), FROM, AS_OF,
                BigDecimal.ZERO, new BigDecimal("100"));
        assertThat(representatives).hasSize(1);
        assertThat(representatives.getFirst().detection()).isSameAs(latest);
        assertThat(representatives.getFirst().missingFeatures()).contains("DAILY_DATA_MISSING");
        assertThat(evaluator.ranked(List.of(old, latest), FROM, AS_OF,
                BigDecimal.ZERO, new BigDecimal("100"), 10)).isEmpty();
    }

    @Test
    void weakDailyTrendAndStaleLatestCandleAreNotQualified() {
        ScannerDetection signal = signal(AS_OF.minus(Duration.ofMinutes(15)));
        DailyMovingAverageFeature ready = dailyReady();
        when(daily.calculate(signal)).thenReturn(ready);
        when(scorer.score(eq(signal), any(), any())).thenReturn(new ScoreResult(new BigDecimal("80"), "{}", "{}"));
        List<StockCandle> oldCandles = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> candle(signal.getDetectedAt().minus(Duration.ofMinutes(20 - index * 5L)),
                        signal.getDetectedAt()))
                .toList();
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("5M"), eq(FROM), any())).thenReturn(oldCandles);
        assertThat(evaluator.assess(signal, FROM, AS_OF, BigDecimal.ZERO, new BigDecimal("100")).reason())
                .isEqualTo("LATEST_CANDLE_STALE");

        DailyMovingAverageFeature weak = dailyReady();
        when(weak.ma20Rising()).thenReturn(false);
        when(daily.calculate(signal)).thenReturn(weak);
        assertThat(evaluator.assess(signal, FROM, AS_OF, BigDecimal.ZERO, new BigDecimal("100")).reason())
                .isEqualTo("DAILY_TREND_WEAK");
    }

    @Test
    void liquidityAndOverextensionCannotBeHiddenByHighScore() {
        ScannerDetection signal = signal(AS_OF);
        DailyMovingAverageFeature ready = dailyReady();
        when(daily.calculate(signal)).thenReturn(ready);
        when(scorer.score(eq(signal), any(), any())).thenReturn(new ScoreResult(new BigDecimal("95"), "{}", "{}"));
        List<StockCandle> rows = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> candle(AS_OF.minus(Duration.ofMinutes(20 - index * 5L)), AS_OF))
                .toList();
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("5M"), eq(FROM), any())).thenReturn(rows);
        when(signal.getDailyValue()).thenReturn(new BigDecimal("900000000"));
        assertThat(evaluator.assess(signal, FROM, AS_OF, BigDecimal.ZERO, new BigDecimal("100")).reason())
                .isEqualTo("LOW_LIQUIDITY");
        when(signal.getDailyValue()).thenReturn(new BigDecimal("2000000000"));
        when(ready.ma20()).thenReturn(new BigDecimal("8000"));
        assertThat(evaluator.assess(signal, FROM, AS_OF, BigDecimal.ZERO, new BigDecimal("100")).reason())
                .isEqualTo("OVEREXTENDED");
    }

    private ScannerDetection signal(Instant at) {
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(1L);
        when(stock.isActive()).thenReturn(true);
        ScannerDetection detection = mock(ScannerDetection.class);
        when(detection.getStock()).thenReturn(stock);
        when(detection.getDetectedAt()).thenReturn(at);
        when(detection.getReceivedAt()).thenReturn(at);
        when(detection.getOpportunityScore()).thenReturn(new BigDecimal("60"));
        when(detection.getRiskScore()).thenReturn(new BigDecimal("20"));
        when(detection.getVolumeRatio()).thenReturn(BigDecimal.ONE);
        when(detection.getDetectedPrice()).thenReturn(new BigDecimal("10000"));
        when(detection.getDailyValue()).thenReturn(new BigDecimal("2000000000"));
        when(detection.getFeatureSnapshot()).thenReturn("{\"vwapDistanceRate\":1,\"dayHighDistanceRate\":1,\"tradeStrength\":100}");
        return detection;
    }

    private DailyMovingAverageFeature dailyReady() {
        DailyMovingAverageFeature value = mock(DailyMovingAverageFeature.class);
        when(value.ready()).thenReturn(true);
        when(value.candleCount()).thenReturn(21);
        when(value.ma20()).thenReturn(new BigDecimal("9000"));
        when(value.closeAboveMa20()).thenReturn(true);
        when(value.ma20Rising()).thenReturn(true);
        return value;
    }

    private StockCandle candle(Instant start, Instant received) {
        StockCandle candle = mock(StockCandle.class);
        when(candle.getStartTime()).thenReturn(start);
        when(candle.getUpdatedAt()).thenReturn(received);
        when(candle.isFinalCandle()).thenReturn(true);
        when(candle.getClose()).thenReturn(new BigDecimal("10000"));
        when(candle.getTradingValue()).thenReturn(new BigDecimal("100000000"));
        return candle;
    }
}
