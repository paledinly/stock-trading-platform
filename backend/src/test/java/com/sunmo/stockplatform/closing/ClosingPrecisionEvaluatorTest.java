/*
 * ClosingPrecisionEvaluator(정밀 후보 평가기)가 Scanner 탐지 1건을 마감 추천 조건으로 판정하는 규칙을 확인하는 테스트예요.
 * 평가 시각 이후에 수신된 봉이나 탐지를 "그때 이미 알 수 있었던 정보"처럼 쓰면 성과가 부풀려지기 때문에,
 * point-in-time(평가 시점 기준 가용 정보만 사용) 규칙과 유동성·과열·결측 같은 제외 사유가 점수로 가려지지 않는지 지켜요.
 */
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

/**
 * 시험 감독관처럼 정밀 후보가 평가 마감 전에 받은 자료만으로 조건을 통과하는지 정확히 판정하는지 확인해요.
 *
 * <p>의존 객체는 모두 목(mock, 가짜 객체)으로 만들고, 점수는 scorer 목이 고정값을 돌려줘요.
 * AS_OF는 15:20 KST(06:20Z), FROM은 14:30 KST 평가 시작 시각이에요. 기본 evaluator는 수신 유예(grace) 10초 설정이에요.
 */
class ClosingPrecisionEvaluatorTest {
    private static final Instant AS_OF = Instant.parse("2026-09-16T06:20:00Z");
    private static final Instant FROM = Instant.parse("2026-09-16T05:30:00Z");
    private final StockCandleRepository candles = mock(StockCandleRepository.class);
    private final IntradayMovingAverageService intraday = mock(IntradayMovingAverageService.class);
    private final DailyMovingAverageService daily = mock(DailyMovingAverageService.class);
    private final ClosingRecommendationScorer scorer = mock(ClosingRecommendationScorer.class);
    private final ClosingPrecisionEvaluator evaluator = new ClosingPrecisionEvaluator(scorer, intraday, daily,
            candles, new ClosingRecommendationProperties(20, 4, new BigDecimal("55"),
                    LocalTime.of(14, 30), LocalTime.of(15, 0), LocalTime.of(15, 20), Duration.ofSeconds(10)), new ObjectMapper(), new ClosingTradingCalendar(
                            new com.sunmo.stockplatform.market.config.MarketWideScheduleProperties(false, 0, 0,
                                    false, null, null, null, null, null, List.of())));

    /**
     * 같은 판정(assess)이 순위(ranked)도 결정하고, 평가 시각 이후 늦게 수정된 봉이 있으면 후보가 제외되는지 확인해요.
     *
     * <p>입력: 확정 5분봉 4개(마지막 봉은 AS_OF+5초에 저장, 유예 10초 안), 이후 마지막 봉을 AS_OF+11초에 수정된 봉으로 바꿔요.
     * <p>기대: 처음엔 QUALIFIED이고 순위에 포함, 늦은 수정 뒤엔 RECEIVED_AFTER_EVALUATION이고 순위가 비어요.
     */
    @Test
    void sameAssessmentControlsRankingAndExcludesLateCandleRevision() {
        // 준비: 일봉 이동평균은 준비 완료, 점수는 70점으로 고정해요.
        ScannerDetection signal = signal(AS_OF);
        DailyMovingAverageFeature ready = dailyReady();
        when(daily.calculate(signal, AS_OF.plusSeconds(10))).thenReturn(ready);
        when(scorer.score(eq(signal), any(), any())).thenReturn(new ScoreResult(new BigDecimal("70"), "{}", "{}"));
        List<StockCandle> rows = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> candle(AS_OF.minus(Duration.ofMinutes(20 - index * 5L)),
                        index == 3 ? AS_OF.plusSeconds(5) : AS_OF.minusSeconds(1)))
                .toList();
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("5M"), eq(FROM), any())).thenReturn(rows);

        // 실행/확인 1: 유예 안에 저장된 봉만 있으면 통과하고 순위에도 들어가요.
        var accepted = evaluator.assess(signal, FROM, AS_OF, BigDecimal.ZERO, new BigDecimal("100"));
        assertThat(accepted.reason()).isEqualTo("QUALIFIED");
        assertThat(evaluator.ranked(List.of(signal), FROM, AS_OF, BigDecimal.ZERO, new BigDecimal("100"), 10))
                .extracting(ClosingPrecisionEvaluator.Assessment::detection).containsExactly(signal);

        // 실행/확인 2: 필요한 봉이 유예(10초)를 넘겨 수정되면 늦은 입력으로 보고 제외해요.
        StockCandle revisedAfterCutoff = candle(AS_OF.minus(Duration.ofMinutes(5)), AS_OF.plusSeconds(11));
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("5M"), eq(FROM), any())).thenReturn(List.of(rows.get(0), rows.get(1), rows.get(2), revisedAfterCutoff));
        var excluded = evaluator.assess(signal, FROM, AS_OF, BigDecimal.ZERO, new BigDecimal("100"));
        assertThat(excluded.reason()).isEqualTo("RECEIVED_AFTER_EVALUATION");
        assertThat(evaluator.ranked(List.of(signal), FROM, AS_OF, BigDecimal.ZERO, new BigDecimal("100"), 10)).isEmpty();
    }

    /**
     * 판정에 쓰지 않는 오래된 구간의 봉이 늦게 백필(backfill, 나중에 채워 넣기)돼도 판정이 무효가 되지 않는지 확인해요.
     *
     * <p>입력: FROM 시각 봉 1개가 AS_OF+11초에 저장되고, 필요한 최근 4개 봉은 모두 AS_OF 이전에 저장돼 있어요.
     * <p>기대: QUALIFIED이고 missingFeatures에 RECEIVED_AFTER_EVALUATION이 없어요.
     */
    @Test
    void unrelatedLateBackfillDoesNotInvalidateRequiredCandles() {
        ScannerDetection signal = signal(AS_OF);
        DailyMovingAverageFeature ready = dailyReady();
        when(daily.calculate(signal, AS_OF.plusSeconds(10))).thenReturn(ready);
        when(scorer.score(eq(signal), any(), any())).thenReturn(new ScoreResult(new BigDecimal("70"), "{}", "{}"));
        List<StockCandle> required = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> candle(AS_OF.minus(Duration.ofMinutes(20 - index * 5L)), AS_OF.minusSeconds(1)))
                .toList();
        StockCandle unrelatedLateBackfill = candle(FROM, AS_OF.plusSeconds(11));
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("5M"), eq(FROM), any())).thenReturn(List.of(unrelatedLateBackfill,
                        required.get(0), required.get(1), required.get(2), required.get(3)));

        var assessment = evaluator.assess(signal, FROM, AS_OF, BigDecimal.ZERO, new BigDecimal("100"));

        assertThat(assessment.reason()).isEqualTo("QUALIFIED");
        assertThat(assessment.missingFeatures()).doesNotContain("RECEIVED_AFTER_EVALUATION");
    }

    /**
     * 수신 유예 0초인 15:15 입력 마감에서, 마감 시각에야 끝나는 봉과 마감 이후 수신된 탐지가 "늦은 수정"이 아니라 "미가용 입력"으로 빠지는지 확인해요(v9_1 규칙).
     *
     * <p>입력: 15:10 탐지(clean)와 15:15 탐지(boundary, 15:15:02 수신), 14:50~15:05 봉은 정상 저장, 15:10 봉은 15:15:03 저장이에요.
     * <p>기대: clean만 대표 후보로 남아 QUALIFIED이고 lateCandleRevision=false, 마지막 확정봉은 15:05 시작 봉이에요.
     * 이어서 마감 전에 끝난 15:05 봉이 마감 뒤에 저장되면 RECEIVED_AFTER_EVALUATION으로 제외돼요.
     */
    @Test
    void boundaryBarAndDetectionUnavailableAtZeroGraceFreezeAreExcludedNotLate() {
        // 준비: 15:15 입력 마감, 유예 0초 설정으로 별도 evaluator를 만들어요.
        Instant freeze = Instant.parse("2026-10-13T06:15:00Z");
        ClosingPrecisionEvaluator zeroGrace = new ClosingPrecisionEvaluator(scorer, intraday, daily, candles,
                new ClosingRecommendationProperties(20, 4, new BigDecimal("55"), LocalTime.of(14, 30),
                        LocalTime.of(15, 15), LocalTime.of(15, 20), Duration.ZERO), new ObjectMapper(),
                new ClosingTradingCalendar(new com.sunmo.stockplatform.market.config.MarketWideScheduleProperties(
                        false, 0, 0, false, null, null, null, null, null, List.of())));
        ScannerDetection clean = signal(freeze.minus(Duration.ofMinutes(5)));
        when(clean.getReceivedAt()).thenReturn(freeze.minus(Duration.ofMinutes(5)).plusSeconds(2));
        ScannerDetection boundary = signal(freeze);
        when(boundary.getReceivedAt()).thenReturn(freeze.plusSeconds(2));
        DailyMovingAverageFeature ready = dailyReady();
        when(daily.calculate(clean, freeze)).thenReturn(ready);
        when(scorer.score(eq(clean), any(), any())).thenReturn(new ScoreResult(new BigDecimal("70"), "{}", "{}"));
        // 14:50..15:05 bars were stored seconds after they closed; the 15:10 bar closes at 15:15:00 and is stored 3s later.
        List<StockCandle> stored = new java.util.ArrayList<>(java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> {
                    Instant start = freeze.minus(Duration.ofMinutes(25 - index * 5L));
                    return candle(start, start.plus(Duration.ofMinutes(5)).plusSeconds(2));
                }).toList());
        stored.add(candle(freeze.minus(Duration.ofMinutes(5)), freeze.plusSeconds(3)));
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("5M"), any(), any())).thenReturn(stored);

        // 실행: 두 탐지 중 종목별 대표 후보를 고르게 해요.
        var representatives = zeroGrace.representatives(List.of(clean, boundary), freeze.minus(Duration.ofMinutes(45)),
                freeze, BigDecimal.ZERO, new BigDecimal("100"));

        // 확인: 마감 이후 수신된 boundary 탐지와 15:10 봉은 판정 입력에서 빠져요.
        assertThat(representatives).extracting(ClosingPrecisionEvaluator.Assessment::detection).containsExactly(clean);
        assertThat(representatives.getFirst().reason()).isEqualTo("QUALIFIED");
        assertThat(representatives.getFirst().dataReadiness()).containsEntry("lateCandleRevision", false)
                .containsEntry("lastFinalCandleAt", freeze.minus(Duration.ofMinutes(10)).toString());

        // A bar that closed before the freeze but was stored after it is still a late revision.
        stored.set(3, candle(freeze.minus(Duration.ofMinutes(10)), freeze.plusSeconds(3)));
        assertThat(zeroGrace.assess(clean, freeze.minus(Duration.ofMinutes(45)), freeze, BigDecimal.ZERO,
                new BigDecimal("100")).reason()).isEqualTo("RECEIVED_AFTER_EVALUATION");
    }

    /**
     * 최신 탐지가 일봉 이력 결측으로 탈락하면, 더 오래된 탐지로 대신 통과시키지 않는지 확인해요.
     *
     * <p>입력: 10분 전 탐지(old)와 최신 탐지(latest), latest의 일봉 이동평균은 비어 있어요(DailyMovingAverageFeature.empty).
     * <p>기대: 대표 후보는 latest 1건이고 DAILY_DATA_MISSING이 기록되며, 순위 결과는 비어요.
     */
    @Test
    void missingDailyHistoryAndLatestRejectedSignalCannotReuseOlderSignal() {
        ScannerDetection old = signal(AS_OF.minus(Duration.ofMinutes(10)));
        ScannerDetection latest = signal(AS_OF);
        when(daily.calculate(latest, AS_OF.plusSeconds(10))).thenReturn(DailyMovingAverageFeature.empty(0));
        when(scorer.score(eq(latest), any(), any())).thenReturn(new ScoreResult(new BigDecimal("80"), "{}", "{}"));

        var representatives = evaluator.representatives(List.of(old, latest), FROM, AS_OF,
                BigDecimal.ZERO, new BigDecimal("100"));
        assertThat(representatives).hasSize(1);
        assertThat(representatives.getFirst().detection()).isSameAs(latest);
        assertThat(representatives.getFirst().missingFeatures()).contains("DAILY_DATA_MISSING");
        assertThat(evaluator.ranked(List.of(old, latest), FROM, AS_OF,
                BigDecimal.ZERO, new BigDecimal("100"), 10)).isEmpty();
    }

    /**
     * 최근 봉이 오래됐거나 일봉 추세가 약하면 점수가 높아도 통과하지 않는지 확인해요.
     *
     * <p>입력: 15분 전 탐지 시각까지만 있는 봉(평가 시각 기준 오래됨), 이후 MA20 상승 여부를 false로 바꿔요.
     * <p>기대: 먼저 LATEST_CANDLE_STALE, 그다음 DAILY_TREND_WEAK가 사유로 나와요.
     */
    @Test
    void weakDailyTrendAndStaleLatestCandleAreNotQualified() {
        ScannerDetection signal = signal(AS_OF.minus(Duration.ofMinutes(15)));
        DailyMovingAverageFeature ready = dailyReady();
        when(daily.calculate(signal, AS_OF.plusSeconds(10))).thenReturn(ready);
        when(scorer.score(eq(signal), any(), any())).thenReturn(new ScoreResult(new BigDecimal("80"), "{}", "{}"));
        List<StockCandle> oldCandles = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> candle(signal.getDetectedAt().minus(Duration.ofMinutes(20 - index * 5L)),
                        signal.getDetectedAt()))
                .toList();
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("5M"), eq(FROM), any())).thenReturn(oldCandles);
        assertThat(evaluator.assess(signal, FROM, AS_OF, BigDecimal.ZERO, new BigDecimal("100")).reason())
                .isEqualTo("LATEST_CANDLE_STALE");

        // 일봉 MA20이 상승하지 않는 경우로 바꿔 다시 판정해요.
        DailyMovingAverageFeature weak = dailyReady();
        when(weak.ma20Rising()).thenReturn(false);
        when(daily.calculate(signal, AS_OF.plusSeconds(10))).thenReturn(weak);
        assertThat(evaluator.assess(signal, FROM, AS_OF, BigDecimal.ZERO, new BigDecimal("100")).reason())
                .isEqualTo("DAILY_TREND_WEAK");
    }

    /**
     * 높은 점수(95점)로도 유동성 부족과 과열(이격 과다)을 가릴 수 없는지 확인해요.
     *
     * <p>입력: 당일 거래대금 9억 원, 이후 거래대금 20억 원이지만 가격 10000원이 MA20 8000원보다 크게 높은 상황이에요.
     * <p>기대: 각각 LOW_LIQUIDITY, OVEREXTENDED로 제외돼요.
     */
    @Test
    void liquidityAndOverextensionCannotBeHiddenByHighScore() {
        ScannerDetection signal = signal(AS_OF);
        DailyMovingAverageFeature ready = dailyReady();
        when(daily.calculate(signal, AS_OF.plusSeconds(10))).thenReturn(ready);
        when(scorer.score(eq(signal), any(), any())).thenReturn(new ScoreResult(new BigDecimal("95"), "{}", "{}"));
        List<StockCandle> rows = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> candle(AS_OF.minus(Duration.ofMinutes(20 - index * 5L)), AS_OF))
                .toList();
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("5M"), eq(FROM), any())).thenReturn(rows);
        when(signal.getDailyValue()).thenReturn(new BigDecimal("900000000"));
        assertThat(evaluator.assess(signal, FROM, AS_OF, BigDecimal.ZERO, new BigDecimal("100")).reason())
                .isEqualTo("LOW_LIQUIDITY");
        // 거래대금은 충분히 올리고, MA20을 낮춰 가격이 이동평균에서 멀리 떨어진 과열 상태를 만들어요.
        when(signal.getDailyValue()).thenReturn(new BigDecimal("2000000000"));
        when(ready.ma20()).thenReturn(new BigDecimal("8000"));
        assertThat(evaluator.assess(signal, FROM, AS_OF, BigDecimal.ZERO, new BigDecimal("100")).reason())
                .isEqualTo("OVEREXTENDED");
    }

    /**
     * 탐지 수신시각이나 봉 저장시각이 없으면 point-in-time 검증이 된 것으로 보지 않는지 확인해요.
     *
     * <p>입력: receivedAt이 null인 탐지와 createdAt/updatedAt이 null인 봉이에요.
     * <p>기대: RECEIVED_AT_MISSING과 CANDLE_TIMESTAMP_MISSING이 기록되고, 대표 사유는 RECEIVED_AT_MISSING이에요.
     */
    @Test
    void missingReceiptOrCandleTimestampIsNotTreatedAsPointInTimeVerified() {
        ScannerDetection signal = signal(AS_OF);
        when(signal.getReceivedAt()).thenReturn(null);
        DailyMovingAverageFeature ready = dailyReady();
        when(daily.calculate(signal, AS_OF.plusSeconds(10))).thenReturn(ready);
        when(scorer.score(eq(signal), any(), any())).thenReturn(new ScoreResult(new BigDecimal("70"), "{}", "{}"));
        StockCandle timestampMissing = candle(AS_OF.minus(Duration.ofMinutes(5)), null);
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("5M"), eq(FROM), any())).thenReturn(List.of(timestampMissing));

        var assessment = evaluator.assess(signal, FROM, AS_OF, BigDecimal.ZERO, new BigDecimal("100"));

        assertThat(assessment.missingFeatures()).contains("RECEIVED_AT_MISSING", "CANDLE_TIMESTAMP_MISSING");
        assertThat(assessment.reason()).isEqualTo("RECEIVED_AT_MISSING");
    }

    /**
     * 탐지 수신도 늦고 필요한 봉도 늦게 수정됐을 때 RECEIVED_AFTER_EVALUATION이 결측 목록에 한 번만 들어가는지 확인해요.
     *
     * <p>입력: 탐지 수신 시각 AS_OF+11초(유예 10초 초과), 마지막 필요 봉도 AS_OF+11초에 수정됐어요.
     * <p>기대: 판정은 그대로 RECEIVED_AFTER_EVALUATION이고 missingFeatures에는 그 값이 정확히 1번 있어요.
     */
    @Test
    void lateReceiptAndLateCandleRevisionAreListedOnce() {
        ScannerDetection signal = signal(AS_OF);
        when(signal.getReceivedAt()).thenReturn(AS_OF.plusSeconds(11));
        DailyMovingAverageFeature ready = dailyReady();
        when(daily.calculate(signal, AS_OF.plusSeconds(10))).thenReturn(ready);
        when(scorer.score(eq(signal), any(), any())).thenReturn(new ScoreResult(new BigDecimal("70"), "{}", "{}"));
        List<StockCandle> rows = new java.util.ArrayList<>(java.util.stream.IntStream.range(0, 3)
                .mapToObj(index -> candle(AS_OF.minus(Duration.ofMinutes(20 - index * 5L)), AS_OF.minusSeconds(1)))
                .toList());
        rows.add(candle(AS_OF.minus(Duration.ofMinutes(5)), AS_OF.plusSeconds(11)));
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("5M"), eq(FROM), any())).thenReturn(rows);

        var assessment = evaluator.assess(signal, FROM, AS_OF, BigDecimal.ZERO, new BigDecimal("100"));

        assertThat(assessment.reason()).isEqualTo("RECEIVED_AFTER_EVALUATION");
        assertThat(assessment.missingFeatures()).containsOnlyOnce("RECEIVED_AFTER_EVALUATION");
    }

    /** 종목 ID 1, 탐지·수신 시각이 같고 기본 조건을 통과할 만한 값을 가진 Scanner 탐지 목을 만들어요. */
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
        when(detection.getFeatureSnapshot()).thenReturn("{\"vwapDistanceRate\":1,\"dayHighDistanceRate\":1,\"tradeStrength\":100,\"tradingHalted\":false}");
        return detection;
    }

    /** MA20 위에 있고 MA20이 상승 중인, 준비 완료된 일봉 이동평균 Feature 목을 만들어요. */
    private DailyMovingAverageFeature dailyReady() {
        DailyMovingAverageFeature value = mock(DailyMovingAverageFeature.class);
        when(value.ready()).thenReturn(true);
        when(value.candleCount()).thenReturn(21);
        when(value.ma20()).thenReturn(new BigDecimal("9000"));
        when(value.closeAboveMa20()).thenReturn(true);
        when(value.ma20Rising()).thenReturn(true);
        return value;
    }

    /** 시작시각과 저장시각(createdAt/updatedAt)을 지정한 확정 5분봉 목을 만들어요. */
    private StockCandle candle(Instant start, Instant received) {
        StockCandle candle = mock(StockCandle.class);
        when(candle.getStartTime()).thenReturn(start);
        when(candle.getCreatedAt()).thenReturn(received);
        when(candle.getUpdatedAt()).thenReturn(received);
        when(candle.isFinalCandle()).thenReturn(true);
        when(candle.getClose()).thenReturn(new BigDecimal("10000"));
        when(candle.getTradingValue()).thenReturn(new BigDecimal("100000000"));
        return candle;
    }
}
