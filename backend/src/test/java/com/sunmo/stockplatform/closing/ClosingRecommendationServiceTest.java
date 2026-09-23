package com.sunmo.stockplatform.closing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.application.*;
import com.sunmo.stockplatform.closing.application.ClosingRecommendationScorer.ScoreResult;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRepository;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRunRepository;
import com.sunmo.stockplatform.closing.infrastructure.OvernightPerformanceRepository;
import com.sunmo.stockplatform.closing.infrastructure.OvernightPositionDecisionRepository;
import com.sunmo.stockplatform.marketwide.domain.*;
import com.sunmo.stockplatform.marketwide.infrastructure.MarketBroadSnapshotRepository;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import com.sunmo.stockplatform.scanner.domain.ScannerType;
import com.sunmo.stockplatform.scanner.infrastructure.ScannerDetectionRepository;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.domain.Market;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClosingRecommendationServiceTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    void precisionCandidateWinsWhenSameStockAlsoHasBroadSnapshot() {
        Fixture fixture = new Fixture();
        LocalDate date = LocalDate.now(SEOUL).minusDays(1);
        Stock stock = fixture.stock(1L);
        ScannerDetection detection = fixture.detection(stock, date.atTime(15, 0).atZone(SEOUL).toInstant());
        MarketBroadSnapshot broad = fixture.snapshot(stock, date.atTime(15, 10).atZone(SEOUL).toInstant(), BroadSnapshotQuality.BROAD_C);
        when(fixture.detections.findBySessionDateAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(eq(date), any()))
                .thenReturn(List.of(detection));
        when(fixture.snapshots.findClosingCandidates(eq(date), any(), any())).thenReturn(List.of(broad));

        var response = fixture.service.generate(date, 10, bd("35"), bd("65"));

        assertThat(response.candidates()).hasSize(1);
        assertThat(response.candidates().getFirst().candidateSource()).isEqualTo("PRECISION");
    }

    @Test
    void includesBroadOnlyCandidateAndExcludesInsufficientSnapshot() {
        Fixture fixture = new Fixture();
        LocalDate date = LocalDate.now(SEOUL).minusDays(1);
        MarketBroadSnapshot valid = fixture.snapshot(fixture.stock(1L), date.atTime(15, 0).atZone(SEOUL).toInstant(), BroadSnapshotQuality.BROAD_C);
        MarketBroadSnapshot insufficient = fixture.snapshot(fixture.stock(2L), date.atTime(15, 1).atZone(SEOUL).toInstant(), BroadSnapshotQuality.INSUFFICIENT);

        when(fixture.detections.findBySessionDateAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(eq(date), any()))
                .thenReturn(List.of());
        when(fixture.snapshots.findClosingCandidates(eq(date), any(), any())).thenReturn(List.of(insufficient, valid));

        var response = fixture.service.generate(date, 10, bd("35"), bd("65"));

        assertThat(response.candidates()).isEmpty();
        assertThat(response.watchCandidates()).isEqualTo(1);
        assertThat(response.exclusionReasons()).containsEntry("BROAD_WATCH_ONLY", 1);
    }

    @Test
    void recommendsWithEnoughSameDayCandlesWithoutHistoricalPerformance() {
        Fixture fixture = new Fixture();
        LocalDate date = LocalDate.now(SEOUL).minusDays(1);
        ScannerDetection detection = fixture.detection(fixture.stock(1L),
                date.atTime(15, 0).atZone(SEOUL).toInstant());
        when(fixture.detections.findBySessionDateAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(eq(date), any()))
                .thenReturn(List.of(detection));

        var response = fixture.service.generate(date, 10, bd("35"), bd("65"));

        assertThat(response.candidates()).hasSize(1);
        assertThat(response.candidates().getFirst().dataQuality()).isEqualTo("PRECISION_A");
        assertThat(response.candidates().getFirst().coverageMinutes()).isEqualTo(20);
        assertThat(response.evaluations().getFirst().dataReadiness()).containsEntry("orderEligible", false)
                .containsEntry("marketRegime", "UNVERIFIED");
    }

    @Test
    void limitedModeKeepsOnlyHighestRankedCandidateAndExplainsTheOther() {
        Fixture fixture = new Fixture();
        LocalDate date = LocalDate.now(SEOUL).minusDays(1);
        Instant at = date.atTime(15, 0).atZone(SEOUL).toInstant();
        ScannerDetection first = fixture.detection(fixture.stock(1L), at);
        ScannerDetection second = fixture.detection(fixture.stock(2L), at);
        when(fixture.detections.findBySessionDateAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(eq(date), any()))
                .thenReturn(List.of(first, second));

        var response = fixture.service.generate(date, 10, bd("35"), bd("65"));

        assertThat(response.candidates()).hasSize(1);
        assertThat(response.evaluations()).hasSize(2);
        assertThat(response.evaluations()).extracting(row -> row.decisionReason())
                .containsExactly("QUALIFIED", "LIMITED_MODE_WATCH");
        assertThat(response.criteria()).containsEntry("limitedModeMaxCandidates", 1)
                .containsEntry("marketSectorAccountChecks", "UNVERIFIED");
    }

    @Test
    void keepsCandidateOnWatchWhenSameDayFinalCandlesAreInsufficient() {
        Fixture fixture = new Fixture();
        LocalDate date = LocalDate.now(SEOUL).minusDays(1);
        ScannerDetection detection = fixture.detection(fixture.stock(1L),
                date.atTime(15, 0).atZone(SEOUL).toInstant());
        when(fixture.detections.findBySessionDateAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(eq(date), any()))
                .thenReturn(List.of(detection));
        when(fixture.candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                anyLong(), eq("5M"), any(), any())).thenReturn(List.of());

        var response = fixture.service.generate(date, 10, bd("35"), bd("65"));

        assertThat(response.candidates()).isEmpty();
        assertThat(response.watchCandidates()).isEqualTo(1);
        assertThat(response.exclusionReasons()).containsEntry("CANDLE_GAP", 1);
    }

    private static BigDecimal bd(String value) { return new BigDecimal(value); }

    @Test
    void retriesReturnSameRunButNewRequestsPreserveSeparateRuns() {
        Fixture fixture = new Fixture();
        LocalDate date = LocalDate.now(SEOUL).minusDays(1);
        var first = fixture.service.generate(date, 10, bd("35"), bd("65"), "retry-1");
        var retry = fixture.service.generate(date, 10, bd("35"), bd("65"), "retry-1");
        var second = fixture.service.generate(date, 10, bd("35"), bd("65"), "retry-2");
        assertThat(retry.runId()).isEqualTo(first.runId());
        assertThat(second.runId()).isNotEqualTo(first.runId());
        assertThat(first.executionMode()).isEqualTo("REPLAY");
        verify(fixture.runs, times(2)).save(any());
        verify(fixture.recommendations, never()).deleteByRecommendationDate(any());
        verifyNoInteractions(fixture.performances, fixture.decisions);
    }

    @Test
    void rejectsReusedKeyWithDifferentThresholds() {
        Fixture fixture = new Fixture();
        LocalDate date = LocalDate.now(SEOUL).minusDays(1);
        fixture.service.generate(date, 10, bd("35"), bd("65"), "retry-key");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                fixture.service.generate(date, 10, bd("40"), bd("65"), "retry-key"))
                .isInstanceOf(com.sunmo.stockplatform.common.error.ApplicationException.class);
        verify(fixture.runs, times(1)).save(any());
    }

    @Test
    void explicitRunDoesNotReadCandidatesFromOtherRuns() {
        Fixture fixture = new Fixture();
        LocalDate date = LocalDate.now(SEOUL).minusDays(1);
        var response = fixture.service.generate(date, 10, bd("35"), bd("65"), "selected-run");
        assertThat(fixture.service.list(date, response.runId())).isEmpty();
        verify(fixture.recommendations).findByRunIdOrderByRankAsc(response.runId());
        verify(fixture.recommendations, never()).findByRecommendationDateOrderByRankAsc(any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.service.list(date.minusDays(1), response.runId()))
                .isInstanceOf(com.sunmo.stockplatform.common.error.ApplicationException.class);
    }

    @Test
    void gapsDoNotCountAsContinuousCoverage() {
        Fixture fixture = new Fixture();
        LocalDate date = LocalDate.now(SEOUL).minusDays(1);
        Instant at = date.atTime(15, 0).atZone(SEOUL).toInstant();
        var detection = fixture.detection(fixture.stock(1L), at);
        when(fixture.detections.findBySessionDateAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(eq(date), any()))
                .thenReturn(List.of(detection));
        var rows = fixture.finalCandles(at.minus(Duration.ofMinutes(30)));
        when(rows.getLast().getStartTime()).thenReturn(at.minus(Duration.ofMinutes(5)));
        when(fixture.candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                anyLong(), eq("5M"), any(), any())).thenReturn(rows);
        var response = fixture.service.generate(date, 10, bd("35"), bd("65"));
        assertThat(response.candidates()).isEmpty();
        assertThat(response.evaluations().getFirst().coverageMinutes()).isEqualTo(5);
    }

    @Test
    void candlesAfterDetectionCannotRepairMissingCoverage() {
        Fixture fixture = new Fixture();
        LocalDate date = LocalDate.now(SEOUL).minusDays(1);
        Instant at = date.atTime(14, 40).atZone(SEOUL).toInstant();
        var detection = fixture.detection(fixture.stock(1L), at);
        when(fixture.detections.findBySessionDateAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(eq(date), any()))
                .thenReturn(List.of(detection));
        var response = fixture.service.generate(date, 10, bd("35"), bd("65"));
        assertThat(response.candidates()).isEmpty();
        assertThat(response.evaluations().getFirst().finalCandles()).isZero();
    }

    @Test
    void latestFilteredSignalPreventsAnOlderSignalFromBeingRecommended() {
        Fixture fixture = new Fixture();
        LocalDate date = LocalDate.now(SEOUL).minusDays(1);
        Stock stock = fixture.stock(1L);
        var older = fixture.detection(stock, date.atTime(14, 50).atZone(SEOUL).toInstant());
        var newer = fixture.detection(stock, date.atTime(14, 59).atZone(SEOUL).toInstant());
        when(newer.getRiskScore()).thenReturn(bd("90"));
        when(fixture.detections.findBySessionDateAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(eq(date), any()))
                .thenReturn(List.of(newer, older));
        var response = fixture.service.generate(date, 10, bd("35"), bd("65"));
        assertThat(response.candidates()).isEmpty();
        assertThat(response.evaluations().getFirst().observedAt()).isEqualTo(newer.getDetectedAt());
    }

    @Test
    void rejectsFutureDateBeforeAnyDatabaseMutation() {
        Fixture fixture = new Fixture();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.service.generate(
                LocalDate.now(SEOUL).plusDays(1), 10, bd("35"), bd("65")))
                .isInstanceOf(com.sunmo.stockplatform.common.error.ApplicationException.class);
        verifyNoInteractions(fixture.recommendations, fixture.performances, fixture.decisions);
    }

    @Test
    void preservesLowScoreCandidateAndCriteriaInImmutableRun() {
        Fixture fixture = new Fixture();
        LocalDate date = LocalDate.now(SEOUL).minusDays(1);
        ScannerDetection detection = fixture.detection(fixture.stock(1L), date.atTime(15, 0).atZone(SEOUL).toInstant());
        when(fixture.detections.findBySessionDateAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(eq(date), any()))
                .thenReturn(List.of(detection));
        when(fixture.precisionScorer.score(any(), any(), any())).thenReturn(new ScoreResult(bd("50.8"), "{}", "{}"));
        var response = fixture.service.generate(date, 10, bd("35"), bd("65"));
        assertThat(response.candidates()).isEmpty();
        assertThat(response.evaluations()).hasSize(1);
        assertThat(response.evaluations().getFirst().finalScore()).isEqualByComparingTo("50.8");
        assertThat(response.evaluations().getFirst().decisionReason()).isEqualTo("LOW_FINAL_SCORE");
        assertThat(response.criteria()).containsEntry("minimumFinalScore", bd("55"));
        var capture = org.mockito.ArgumentCaptor.forClass(com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun.class);
        verify(fixture.runs).save(capture.capture());
        when(fixture.runs.findFirstByRecommendationDateOrderByIdDesc(date))
                .thenReturn(java.util.Optional.of(capture.getValue()));
        assertThat(fixture.service.latestEvaluation(date).evaluations()).isEqualTo(response.evaluations());
        verify(fixture.recommendations).saveAll(argThat(rows -> !rows.iterator().hasNext()));
    }

    @Test
    void defaultEvaluationPrefersOfficialForwardRunOverNewerReplay() throws Exception {
        Fixture fixture = new Fixture();
        LocalDate date = LocalDate.now(SEOUL).minusDays(1);
        var response = fixture.service.generate(date, 10, bd("35"), bd("65"));
        Instant now = Instant.now();
        ClosingRecommendationRun forward = new ClosingRecommendationRun(date, now, "test", "FORWARD", now,
                "{}", "forward", "forward-key");
        org.springframework.test.util.ReflectionTestUtils.setField(forward, "id", 27L);
        forward.complete(fixture.mapper.writeValueAsString(response), now);
        ClosingRecommendationRun replay = new ClosingRecommendationRun(date, now.plusSeconds(1), "test", "REPLAY",
                now, "{}", "replay", "replay-key");
        org.springframework.test.util.ReflectionTestUtils.setField(replay, "id", 28L);
        replay.complete(fixture.mapper.writeValueAsString(response), now.plusSeconds(1));
        when(fixture.runs.findFirstByRecommendationDateAndExecutionModeOrderByIdDesc(date, "FORWARD"))
                .thenReturn(java.util.Optional.of(forward));
        when(fixture.runs.findFirstByRecommendationDateOrderByIdDesc(date))
                .thenReturn(java.util.Optional.of(replay));

        assertThat(fixture.service.latestEvaluation(date).runId()).isEqualTo(27L);
        assertThat(fixture.service.latestEvaluation(date).executionMode()).isEqualTo("FORWARD");
        verify(fixture.runs, never()).findFirstByRecommendationDateOrderByIdDesc(date);
    }

    @Test
    void rejectsForwardEvaluationBeforeTheFixedDecisionTime() {
        LocalDate date = LocalDate.of(2026, 9, 22);
        Fixture fixture = new Fixture(Clock.fixed(date.atTime(14, 59).atZone(SEOUL).toInstant(), ZoneOffset.UTC),
                mock(com.sunmo.stockplatform.market.application.RealtimeDiagnostics.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.service.generate(date, 10, bd("35"), bd("65")))
                .isInstanceOf(com.sunmo.stockplatform.common.error.ApplicationException.class)
                .hasMessageContaining("15:00");
        verifyNoInteractions(fixture.runs);
    }

    @Test
    void freezesForwardEvaluationAt1500AndRequiresAFreshTick() {
        LocalDate date = LocalDate.of(2026, 9, 22);
        Instant decisionAt = date.atTime(15, 0).atZone(SEOUL).toInstant();
        var realtime = mock(com.sunmo.stockplatform.market.application.RealtimeDiagnostics.class);
        when(realtime.lastTickAt()).thenReturn(decisionAt);
        Fixture fixture = new Fixture(Clock.fixed(date.atTime(15, 5).atZone(SEOUL).toInstant(), ZoneOffset.UTC), realtime);

        var response = fixture.service.generate(date, 10, bd("35"), bd("65"));

        assertThat(response.executionMode()).isEqualTo("FORWARD");
        assertThat(response.evaluationEnd()).isEqualTo(decisionAt);
        assertThat(response.criteria()).containsEntry("entryDeadline", "15:20")
                .containsEntry("entryModel", "NEXT_FINAL_5M_OPEN");
    }

    @Test
    void rejectsForwardEvaluationWhenRealtimeTicksWereStaleAt1500() {
        LocalDate date = LocalDate.of(2026, 9, 22);
        var realtime = mock(com.sunmo.stockplatform.market.application.RealtimeDiagnostics.class);
        when(realtime.lastTickAt()).thenReturn(date.atTime(14, 0).atZone(SEOUL).toInstant());
        Fixture fixture = new Fixture(Clock.fixed(date.atTime(15, 5).atZone(SEOUL).toInstant(), ZoneOffset.UTC), realtime);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.service.generate(date, 10, bd("35"), bd("65")))
                .isInstanceOf(com.sunmo.stockplatform.common.error.ApplicationException.class)
                .hasMessageContaining("실시간 시세");
        verifyNoInteractions(fixture.runs);
    }

    private static final class Fixture {
        final ScannerDetectionRepository detections = mock(ScannerDetectionRepository.class);
        final ClosingRecommendationRepository recommendations = mock(ClosingRecommendationRepository.class);
        final ClosingRecommendationScorer precisionScorer = mock(ClosingRecommendationScorer.class);
        final IntradayMovingAverageService intraday = mock(IntradayMovingAverageService.class);
        final DailyMovingAverageService daily = mock(DailyMovingAverageService.class);
        final MarketBroadSnapshotRepository snapshots = mock(MarketBroadSnapshotRepository.class);
        final OvernightPerformanceRepository performances = mock(OvernightPerformanceRepository.class);
        final OvernightPositionDecisionRepository decisions = mock(OvernightPositionDecisionRepository.class);
        final StockCandleRepository candles = mock(StockCandleRepository.class);
        final ClosingRecommendationRunRepository runs = mock(ClosingRecommendationRunRepository.class);
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final ClosingRecommendationService service;

        Fixture() {
            this(Clock.systemUTC(), mock(com.sunmo.stockplatform.market.application.RealtimeDiagnostics.class));
        }

        Fixture(Clock clock, com.sunmo.stockplatform.market.application.RealtimeDiagnostics realtime) {
            var stored = new java.util.HashMap<String, com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun>();
            when(runs.save(any())).thenAnswer(invocation -> {
                com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun run = invocation.getArgument(0);
                org.springframework.test.util.ReflectionTestUtils.setField(run, "id", (long) stored.size() + 1);
                stored.put(run.getRequestKey(), run);
                return run;
            });
            when(runs.findByRequestKey(anyString())).thenAnswer(invocation -> java.util.Optional.ofNullable(stored.get(invocation.getArgument(0))));
            when(runs.findById(anyLong())).thenAnswer(invocation -> stored.values().stream()
                    .filter(run -> run.getId().equals(invocation.getArgument(0))).findFirst());
            when(recommendations.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
            when(precisionScorer.score(any(), any(), any())).thenReturn(new ScoreResult(bd("80"), "{}", "{}"));
            DailyMovingAverageFeature readyDaily = mock(DailyMovingAverageFeature.class);
            when(readyDaily.ready()).thenReturn(true);
            when(readyDaily.candleCount()).thenReturn(21);
            when(readyDaily.ma20()).thenReturn(bd("9000"));
            when(readyDaily.closeAboveMa20()).thenReturn(true);
            when(readyDaily.ma20Rising()).thenReturn(true);
            when(daily.calculate(any(ScannerDetection.class))).thenReturn(readyDaily);
            when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                    anyLong(), eq("5M"), any(), any())).thenAnswer(invocation -> {
                        List<StockCandle> values = new java.util.ArrayList<>(finalCandles(
                                ((Instant) invocation.getArgument(2)).plus(Duration.ofMinutes(10))));
                        values.add(finalCandle(((Instant) invocation.getArgument(3)).minus(Duration.ofMinutes(6))));
                        return values;
                    });
            ClosingRecommendationProperties properties = new ClosingRecommendationProperties(20, 4, bd("55"),
                    LocalTime.of(14, 30), LocalTime.of(15, 0), LocalTime.of(15, 20), Duration.ofSeconds(10));
            ClosingTradingCalendar calendar = new ClosingTradingCalendar(
                    new com.sunmo.stockplatform.market.config.MarketWideScheduleProperties(false, 0, 0, false,
                            null, null, null, null, null, List.of()), clock);
            service = new ClosingRecommendationService(detections, recommendations,
                    snapshots, new BroadClosingRecommendationScorer(mapper), mapper,
                    mock(org.springframework.jdbc.core.JdbcTemplate.class), calendar,
                    properties, runs, new ClosingPrecisionEvaluator(precisionScorer, intraday, daily, candles,
                            properties, mapper, calendar), realtime,
                    new com.sunmo.stockplatform.market.config.RealtimeMarketProperties(false,
                            java.net.URI.create("ws://localhost"), Duration.ZERO, Duration.ofHours(1), 10, 41,
                            Duration.ofMinutes(1)));
        }

        List<StockCandle> finalCandles(Instant start) {
            return java.util.stream.IntStream.range(0, 4)
                    .mapToObj(index -> finalCandle(start.plus(Duration.ofMinutes(index * 5L)))).toList();
        }

        StockCandle finalCandle(Instant start) {
            StockCandle candle = mock(StockCandle.class);
            when(candle.isFinalCandle()).thenReturn(true);
            when(candle.getStartTime()).thenReturn(start);
            when(candle.getClose()).thenReturn(bd("10000"));
            when(candle.getTradingValue()).thenReturn(bd("100000000"));
            return candle;
        }

        Stock stock(long id) {
            Stock stock = mock(Stock.class);
            when(stock.getId()).thenReturn(id);
            when(stock.getStockCode()).thenReturn("00000" + id);
            when(stock.getStockName()).thenReturn("stock" + id);
            when(stock.getMarket()).thenReturn(Market.KOSPI);
            when(stock.isActive()).thenReturn(true);
            return stock;
        }

        ScannerDetection detection(Stock stock, Instant at) {
            ScannerDetection detection = mock(ScannerDetection.class);
            when(detection.getStock()).thenReturn(stock);
            when(detection.getDetectedAt()).thenReturn(at);
            when(detection.getDetectedPrice()).thenReturn(bd("10000"));
            when(detection.getOpportunityScore()).thenReturn(bd("75"));
            when(detection.getRiskScore()).thenReturn(bd("20"));
            when(detection.getVolumeRatio()).thenReturn(bd("2"));
            when(detection.getDailyValue()).thenReturn(bd("2000000000"));
            when(detection.getType()).thenReturn(ScannerType.VWAP_BREAKOUT);
            when(detection.getFeatureSnapshot()).thenReturn("{\"vwapDistanceRate\":1,\"dayHighDistanceRate\":1,\"tradeStrength\":120}");
            return detection;
        }

        MarketBroadSnapshot snapshot(Stock stock, Instant at, BroadSnapshotQuality quality) {
            MarketBroadSnapshot snapshot = mock(MarketBroadSnapshot.class);
            Long stockId = stock.getId();
            when(snapshot.getId()).thenReturn(stockId);
            when(snapshot.getStock()).thenReturn(stock);
            when(snapshot.getCapturedAt()).thenReturn(at);
            when(snapshot.getDataQuality()).thenReturn(quality);
            when(snapshot.getCollectionStatus()).thenReturn(quality == BroadSnapshotQuality.BROAD_C
                    ? BroadSnapshotStatus.COLLECTED : BroadSnapshotStatus.QUOTE_FAILED);
            when(snapshot.getCurrentPrice()).thenReturn(bd("10000"));
            when(snapshot.getAccumulatedTradingValue()).thenReturn(bd("5000000000"));
            when(snapshot.getChangeRate()).thenReturn(bd("2"));
            when(snapshot.getBroadScore()).thenReturn(bd("80"));
            when(snapshot.getRankingSources()).thenReturn("[\"TURNOVER\"]");
            return snapshot;
        }

    }
}
