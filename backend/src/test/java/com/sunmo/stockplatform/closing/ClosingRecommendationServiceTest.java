/*
 * ClosingRecommendationService(마감 추천 생성 서비스)가 후보를 모아 평가하고 추천 run(실행 기록)을 저장하는 전체 흐름을 확인하는 테스트예요.
 * 입력 마감 이후 자료 제외, Precision/Broad 후보 구분, 최대 5종목 제한, 재시도 시 같은 run 재사용, FORWARD/REPLAY 구분이 깨지면
 * 공식 Forward 표본과 과거 기록이 섞이거나 덮어써질 수 있어서 중요해요.
 */
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

/**
 * 심판처럼 마감 추천 한 번의 실행이 정해진 규칙(시각, 후보 출처, 개수 제한, 재실행 규칙)을 지키는지 확인해요.
 *
 * <p>대부분의 테스트는 어제 날짜를 평가해 REPLAY(과거 재현) 모드로 실행돼요.
 * 오늘 거래일을 고정 시계 Clock(테스트용으로 멈춰 둔 시계)으로 평가할 때만 FORWARD(실시간 공식 실행) 모드가 돼요.
 */
class ClosingRecommendationServiceTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * v9_1 15:15 입력 마감에서 마감 이후 탐지를 제외하고, 수동 요청과 자동 공식 요청이 같은 run 하나를 공유하는지 확인해요.
     *
     * <p>입력: 2026-09-22 15:15:30 고정 시계, 마지막 틱은 15:15, 탐지는 15:15:01(마감 1초 뒤)이에요.
     * 같은 날 "manual-click" 키와 공식 키로 두 번 생성을 요청해요.
     * <p>기대: 평가 종료는 15:15, 입력 수신 마감 기준이 기록되고, 평가 대상은 비며, 두 응답의 runId가 같고 run 저장은 1회예요.
     */
    @Test void new1515CutoffExcludesFutureSignalsAndManualAutomaticShareOneRun() {
        LocalDate date = LocalDate.of(2026,9,22);
        Instant freeze = date.atTime(15,15).atZone(SEOUL).toInstant();
        var realtime = mock(com.sunmo.stockplatform.market.application.RealtimeDiagnostics.class);
        when(realtime.lastTickAt()).thenReturn(freeze);
        Fixture fixture = new Fixture(Clock.fixed(freeze.plusSeconds(30), ZoneOffset.UTC), realtime, LocalTime.of(15,15), Duration.ZERO);
        var future = fixture.detection(fixture.stock(1L), freeze.plusSeconds(1));
        when(fixture.detections.findBySessionDateAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(eq(date),any()))
                .thenReturn(List.of(future));
        // 실행: FORWARD 모드에서는 요청 키와 무관하게 공식 키로 묶이는 것으로 보여요.
        var first = fixture.service.generate(date,10,bd("35"),bd("65"),"manual-click");
        var second = fixture.service.generate(date,10,bd("35"),bd("65"),"official-"+date+"-"+ClosingRecommendation.STRATEGY_VERSION);
        // 확인: 마감 이후 탐지는 평가에서 빠지고, 두 요청이 같은 run을 돌려줘요.
        assertThat(first.evaluationEnd()).isEqualTo(freeze);
        assertThat(first.criteria()).containsEntry("inputReceiptDeadlineInstant",freeze.toString());
        assertThat(first.evaluations()).isEmpty();
        assertThat(second.runId()).isEqualTo(first.runId());
        verify(fixture.runs,times(1)).save(any());
    }

    /**
     * 같은 종목에 Precision 탐지와 Broad 스냅샷이 모두 있으면 Precision 후보가 우선하는지 확인해요.
     *
     * <p>입력: 15:00 Precision 탐지와 15:10 Broad 스냅샷(BROAD_C)이 같은 종목에 있어요.
     * <p>기대: 추천 후보는 1건이고 출처는 PRECISION이에요.
     */
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

    /**
     * Broad 전용 후보는 최종 추천이 아니라 관찰(WATCH)로만 가고, 품질 미달 스냅샷은 제외되는지 확인해요.
     *
     * <p>입력: Precision 탐지는 없고, 종목 1의 BROAD_C 스냅샷과 종목 2의 INSUFFICIENT 스냅샷이 있어요.
     * <p>기대: 추천 후보는 없고, 관찰 후보 1건, 제외 사유 BROAD_WATCH_ONLY 1건이에요.
     */
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

    /**
     * 과거 성과 이력이 없어도 당일 확정 5분봉이 충분하면 추천되는지 확인해요.
     *
     * <p>입력: 15:00 Precision 탐지 1건, 기본 Fixture 봉(연속 확정봉)이에요.
     * <p>기대: 후보 1건, 데이터 품질 PRECISION_A, 연속 커버리지 20분이고, 주문 가능(orderEligible)=false, 시장 국면은 UNVERIFIED로 기록돼요.
     */
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

    /**
     * 제한 모드에서 조건 통과 후보 중 상위 5종목만 추천하고 나머지는 사유를 남기는지 확인해요.
     *
     * <p>입력: 같은 시각, 같은 조건의 탐지 6건이에요.
     * <p>기대: 추천 5건, 평가 6건, 여섯 번째는 LIMITED_MODE_WATCH이고 기준에 limitedModeMaxCandidates=5가 기록돼요.
     */
    @Test
    void limitedModeKeepsTopFiveCandidatesAndExplainsTheOther() {
        Fixture fixture = new Fixture();
        LocalDate date = LocalDate.now(SEOUL).minusDays(1);
        Instant at = date.atTime(15, 0).atZone(SEOUL).toInstant();
        List<ScannerDetection> signals = java.util.stream.LongStream.rangeClosed(1, 6)
                .mapToObj(id -> fixture.detection(fixture.stock(id), at)).toList();
        when(fixture.detections.findBySessionDateAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(eq(date), any()))
                .thenReturn(signals);

        var response = fixture.service.generate(date, 10, bd("35"), bd("65"));

        assertThat(response.candidates()).hasSize(5);
        assertThat(response.evaluations()).hasSize(6);
        assertThat(response.evaluations()).extracting(row -> row.decisionReason())
                .containsExactly("QUALIFIED", "QUALIFIED", "QUALIFIED", "QUALIFIED", "QUALIFIED", "LIMITED_MODE_WATCH");
        assertThat(response.criteria()).containsEntry("limitedModeMaxCandidates", 5)
                .containsEntry("marketSectorAccountChecks", "UNVERIFIED");
    }

    /**
     * 당일 확정봉이 부족하면 추천하지 않고 관찰로 남기는지 확인해요.
     *
     * <p>입력: 15:00 탐지 1건, 5분봉 조회 결과는 빈 목록이에요.
     * <p>기대: 추천 없음, 관찰 1건, 제외 사유 CANDLE_GAP 1건이에요.
     */
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

    /** 문자열로 BigDecimal을 만드는 짧은 도우미예요. */
    private static BigDecimal bd(String value) { return new BigDecimal(value); }

    /**
     * 같은 요청 키로 재시도하면 같은 run을 돌려주고, 새 요청 키는 별도 run으로 보존하는지 확인해요.
     *
     * <p>입력: 어제 날짜로 retry-1 두 번, retry-2 한 번 생성해요.
     * <p>기대: retry-1끼리는 runId가 같고 retry-2는 다르며 모드는 REPLAY, run 저장은 2회예요.
     * 기존 추천 삭제나 성과·포지션 결정 저장소 접근은 없어요.
     */
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

    /**
     * 같은 요청 키를 다른 임계값으로 다시 쓰면 거부하는지 확인해요.
     *
     * <p>입력: retry-key로 기준 35 생성 뒤, 같은 키로 기준 40 생성을 요청해요.
     * <p>기대: ApplicationException이 나고 run 저장은 1회뿐이에요.
     */
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

    /**
     * runId를 지정한 조회가 다른 run의 추천을 섞어 읽지 않는지 확인해요.
     *
     * <p>입력: 어제 날짜 run 하나를 만들고 그 runId로 목록을 조회해요. 날짜가 다른 조회도 시도해요.
     * <p>기대: runId 기준 조회만 쓰고 날짜 기준 조회는 쓰지 않으며, 날짜가 run과 다르면 ApplicationException이 나요.
     */
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

    /**
     * 중간에 빈 봉이 있으면 연속 커버리지로 세지 않는지 확인해요.
     *
     * <p>입력: 14:30부터 5분봉 4개 중 마지막 봉 시작을 14:55로 바꿔 14:45~14:50 구간이 비게 해요.
     * <p>기대: 추천 없음, 연속 커버리지 5분이에요.
     */
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

    /**
     * 탐지 이후에 생긴 봉으로 탐지 시점의 부족한 커버리지를 메우지 않는지 확인해요.
     *
     * <p>입력: 14:40 탐지, 기본 Fixture 봉은 14:40 이후부터 있어요.
     * <p>기대: 추천 없음, 판정에 쓴 확정봉 수는 0이에요.
     */
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

    /**
     * 같은 종목의 최신 탐지가 필터에 걸리면 더 오래된 탐지로 대신 추천하지 않는지 확인해요.
     *
     * <p>입력: 14:50 탐지(older)와 14:59 탐지(newer, 위험 점수 90)예요.
     * <p>기대: 추천 없음, 평가 기록의 관측 시각은 newer 탐지 시각이에요.
     */
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

    /**
     * 미래 날짜 요청은 DB를 바꾸기 전에 거부하는지 확인해요.
     *
     * <p>입력: 내일 날짜로 생성 요청해요.
     * <p>기대: ApplicationException이 나고 추천·성과·포지션 결정 저장소에 아무 호출도 없어요.
     */
    @Test
    void rejectsFutureDateBeforeAnyDatabaseMutation() {
        Fixture fixture = new Fixture();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.service.generate(
                LocalDate.now(SEOUL).plusDays(1), 10, bd("35"), bd("65")))
                .isInstanceOf(com.sunmo.stockplatform.common.error.ApplicationException.class);
        verifyNoInteractions(fixture.recommendations, fixture.performances, fixture.decisions);
    }

    /**
     * 점수 미달 후보도 평가 기록과 기준값이 바뀌지 않는 run에 그대로 보존되는지 확인해요.
     *
     * <p>입력: 15:00 탐지 1건, 정밀 점수 50.8(최소 55 미만)이에요.
     * <p>기대: 추천 없음, 평가 1건이 LOW_FINAL_SCORE로 남고, 저장된 run에서 다시 읽은 평가가 원래 응답과 같으며, 추천 저장은 빈 목록이에요.
     */
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
        // ArgumentCaptor(호출 인자 포착기)로 저장된 run을 잡아 다시 조회 결과로 돌려줘요.
        var capture = org.mockito.ArgumentCaptor.forClass(com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun.class);
        verify(fixture.runs).save(capture.capture());
        when(fixture.runs.findFirstByRecommendationDateOrderByIdDesc(date))
                .thenReturn(java.util.Optional.of(capture.getValue()));
        assertThat(fixture.service.evaluation(date, null).evaluations()).isEqualTo(response.evaluations());
        verify(fixture.recommendations).saveAll(argThat(rows -> !rows.iterator().hasNext()));
    }

    /**
     * 날짜만으로 평가를 조회하면 더 최신 REPLAY run보다 공식 FORWARD run을 우선하는지 확인해요.
     *
     * <p>입력: id 27 FORWARD run과 1초 뒤 완료된 id 28 REPLAY run이 같은 날짜에 있어요.
     * <p>기대: 조회 결과 runId 27, 모드 FORWARD이고 최신 run 조회는 쓰지 않아요.
     */
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

        assertThat(fixture.service.evaluation(date, null).runId()).isEqualTo(27L);
        assertThat(fixture.service.evaluation(date, null).executionMode()).isEqualTo("FORWARD");
        verify(fixture.runs, never()).findFirstByRecommendationDateOrderByIdDesc(date);
    }

    /**
     * 고정 판정 시각 전에는 FORWARD 평가를 거부하는지 확인해요.
     *
     * <p>입력: 2026-09-22 14:59 고정 시계, 기본 Fixture 입력 마감 15:00이에요.
     * <p>기대: "15:00"을 담은 ApplicationException이 나고 run 저장소 호출이 없어요.
     */
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

    /**
     * FORWARD 평가가 입력 마감 시각(이 Fixture에서는 15:00)으로 고정되고, 신선한 틱을 요구하는 진입 모델을 기록하는지 확인해요.
     *
     * <p>입력: 15:05 고정 시계, 마지막 틱 15:00이에요.
     * <p>기대: 모드 FORWARD, 평가 종료 15:00, 진입 마감 15:20, 진입 모델 FRESH_OBSERVED_TICK_AFTER_COMPLETION이에요.
     */
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
                .containsEntry("entryModel", "FRESH_OBSERVED_TICK_AFTER_COMPLETION");
    }

    /**
     * 마감 시각에 실시간 틱이 오래돼 있으면 FORWARD 평가를 거부하는지 확인해요.
     *
     * <p>입력: 15:05 고정 시계, 마지막 틱 14:00이에요.
     * <p>기대: "실시간 시세"를 담은 ApplicationException이 나고 run 저장소 호출이 없어요.
     */
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

    /**
     * 서비스와 목 저장소들을 한데 묶은 테스트 준비물이에요.
     *
     * <p>run 저장소 목은 요청 키별 메모리 맵에 저장해 실제 저장소처럼 재조회되게 하고, 점수는 80점으로 고정해요.
     * <p>⚠️ 확인 필요: 기본 생성자는 입력 마감 15:00, 유예 10초를 써요. 현재 운영 전략(v9_1, 15:15 마감, 유예 0초)과 달라서
     * 이름에 1500이 들어간 테스트는 설정값에 따른 동작을 확인하는 것으로 보이며, 운영 기본값 자체를 검증하지는 않아요.
     */
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

        /** 실제 UTC 시스템 시계와 기본 실시간 진단 목으로 만들어요(어제 날짜 테스트는 REPLAY가 돼요). */
        Fixture() {
            this(Clock.systemUTC(), mock(com.sunmo.stockplatform.market.application.RealtimeDiagnostics.class));
        }

        /** 지정한 시계와 실시간 진단으로, 입력 마감 15:00·유예 10초 설정을 써서 만들어요. */
        Fixture(Clock clock, com.sunmo.stockplatform.market.application.RealtimeDiagnostics realtime) {
            this(clock, realtime, LocalTime.of(15, 0), Duration.ofSeconds(10));
        }

        /** 시계, 실시간 진단, 입력 마감 시각, 수신 유예를 모두 지정해 서비스를 조립해요. */
        Fixture(Clock clock, com.sunmo.stockplatform.market.application.RealtimeDiagnostics realtime, LocalTime freeze, Duration grace) {
            var stored = new java.util.HashMap<String, com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun>();
            // run 저장 목: 저장 순서대로 id를 매기고 요청 키로 다시 찾을 수 있게 해요.
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
            when(precisionScorer.score(any(ScannerDetection.class))).thenReturn(new ScoreResult(bd("80"), "{}", "{}"));
            DailyMovingAverageFeature readyDaily = mock(DailyMovingAverageFeature.class);
            when(readyDaily.ready()).thenReturn(true);
            when(readyDaily.candleCount()).thenReturn(21);
            when(readyDaily.ma20()).thenReturn(bd("9000"));
            when(readyDaily.closeAboveMa20()).thenReturn(true);
            when(readyDaily.ma20Rising()).thenReturn(true);
            when(daily.calculate(any(ScannerDetection.class), any(Instant.class))).thenReturn(readyDaily);
            // 5분봉 목: 조회 시작 + 10분부터 연속 4개, 그리고 조회 끝 - 6분 시작 봉 1개를 돌려줘요.
            when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                    anyLong(), eq("5M"), any(), any())).thenAnswer(invocation -> {
                        List<StockCandle> values = new java.util.ArrayList<>(finalCandles(
                                ((Instant) invocation.getArgument(2)).plus(Duration.ofMinutes(10))));
                        values.add(finalCandle(((Instant) invocation.getArgument(3)).minus(Duration.ofMinutes(6))));
                        return values;
                    });
            ClosingRecommendationProperties properties = new ClosingRecommendationProperties(20, 4, bd("55"),
                    LocalTime.of(14, 30), freeze, LocalTime.of(15, 20), grace);
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
                            Duration.ofMinutes(1)), mock(ClosingPaperEntryService.class));
        }

        /** start부터 5분 간격으로 이어지는 확정 5분봉 4개를 만들어요. */
        List<StockCandle> finalCandles(Instant start) {
            return java.util.stream.IntStream.range(0, 4)
                    .mapToObj(index -> finalCandle(start.plus(Duration.ofMinutes(index * 5L)))).toList();
        }

        /** 봉이 끝나는 시각(start + 5분)에 저장된 확정 5분봉 목을 만들어요. */
        StockCandle finalCandle(Instant start) {
            StockCandle candle = mock(StockCandle.class);
            when(candle.isFinalCandle()).thenReturn(true);
            when(candle.getStartTime()).thenReturn(start);
            when(candle.getCreatedAt()).thenReturn(start.plus(Duration.ofMinutes(5)));
            when(candle.getUpdatedAt()).thenReturn(start.plus(Duration.ofMinutes(5)));
            when(candle.getClose()).thenReturn(bd("10000"));
            when(candle.getTradingValue()).thenReturn(bd("100000000"));
            return candle;
        }

        /** 활성 KOSPI 종목 목을 만들어요(종목코드는 "00000" + id). */
        Stock stock(long id) {
            Stock stock = mock(Stock.class);
            when(stock.getId()).thenReturn(id);
            when(stock.getStockCode()).thenReturn("00000" + id);
            when(stock.getStockName()).thenReturn("stock" + id);
            when(stock.getMarket()).thenReturn(Market.KOSPI);
            when(stock.isActive()).thenReturn(true);
            return stock;
        }

        /** 기본 조건을 통과할 만한 값의 VWAP_BREAKOUT Scanner 탐지 목을 만들어요. */
        ScannerDetection detection(Stock stock, Instant at) {
            ScannerDetection detection = mock(ScannerDetection.class);
            when(detection.getStock()).thenReturn(stock);
            when(detection.getDetectedAt()).thenReturn(at);
            when(detection.getReceivedAt()).thenReturn(at);
            when(detection.getDetectedPrice()).thenReturn(bd("10000"));
            when(detection.getOpportunityScore()).thenReturn(bd("75"));
            when(detection.getRiskScore()).thenReturn(bd("20"));
            when(detection.getVolumeRatio()).thenReturn(bd("2"));
            when(detection.getDailyValue()).thenReturn(bd("2000000000"));
            when(detection.getType()).thenReturn(ScannerType.VWAP_BREAKOUT);
            when(detection.getFeatureSnapshot()).thenReturn("{\"vwapDistanceRate\":1,\"dayHighDistanceRate\":1,\"tradeStrength\":120,\"tradingHalted\":false}");
            return detection;
        }

        /** 지정 품질의 Broad 스냅샷 목을 만들어요. BROAD_C면 수집 완료, 아니면 QUOTE_FAILED 상태예요. */
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
