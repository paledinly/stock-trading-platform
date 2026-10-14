/*
 * 완료된 FORWARD 마감 평가 run의 후보 전체(SELECTED, WATCH, 제외 후보)를 다음 거래일까지 따라가며 가상 성과를 관측해요.
 * 진입은 가상 진입 기록(또는 구버전 run이면 평가 완료 뒤 첫 5분봉 시가)을 쓰고, 다음 거래일 5분봉으로 목표/손절/종가 청산을 계산해요.
 * ClosingRecommendationController(수동 조회·추적)와 ClosingRecommendationScheduler(장 마감 후 자동 추적)가 써요.
 */
package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.*;
import com.sunmo.stockplatform.closing.domain.*;
import com.sunmo.stockplatform.closing.infrastructure.*;
import com.sunmo.stockplatform.common.error.*;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 공식 추천 성과와 섞지 않고, 평가된 후보 하나하나의 다음날 결과를 따로 기록하는 관측 서비스예요.
 * 오디션 합격자뿐 아니라 대기자·탈락자까지 다음 날 무대에서 어떻게 했는지 따로 적어 두는
 * 관찰 노트라고 생각하면 돼요. 공식 합격자 성적표(OvernightPerformance)와는 다른 노트예요.
 */
@Service
public class ClosingCandidateObservationService {
    private final ClosingRecommendationRunRepository runs;
    private final ClosingCandidateObservationRepository observations;
    private final StockRepository stocks;
    private final StockCandleRepository candles;
    private final ClosingTradingCalendar calendar;
    private final OvernightExecutionSimulator execution;
    private final ObjectMapper mapper;
    private final ClosingPaperEntryService paperEntries;

    /**
     * 관측에 필요한 저장소, 달력, 청산 시뮬레이터, JSON 변환기, 가상 진입 서비스를 받아요.
     */
    public ClosingCandidateObservationService(ClosingRecommendationRunRepository runs,
            ClosingCandidateObservationRepository observations, StockRepository stocks,
            StockCandleRepository candles, ClosingTradingCalendar calendar,
            OvernightExecutionSimulator execution, ObjectMapper mapper, ClosingPaperEntryService paperEntries) {
        this.paperEntries = paperEntries;
        this.runs = runs;
        this.observations = observations;
        this.stocks = stocks;
        this.candles = candles;
        this.calendar = calendar;
        this.execution = execution;
        this.mapper = mapper;
    }

    /**
     * run의 후보마다 관측 행을 만들거나 갱신하고, 지금 시각 기준으로 성과 상태를 다시 평가해요.
     * 트랜잭션(여러 DB 작업을 한 묶음으로 처리하는 단위) 안에서 돌아요.
     * 받는 것: run ID, 목표 수익률(%, 기본 +3), 손절 수익률(%, 기본 -2).
     * 돌려주는 것: 후보 수, SELECTED 수, 비선정 수, 완료 수와 행 목록이 담긴 Report.
     */
    @Transactional
    public Report track(Long runId, BigDecimal target, BigDecimal stop) {
        // 준비: 목표는 양수, 손절은 음수여야 하고, 완료된 FORWARD(실제 장중 실시간 공식 평가) run만 관측해요.
        if (runId == null) throw invalid("Evaluation run is required");
        BigDecimal targetRate = target == null ? new BigDecimal("3") : target;
        BigDecimal stopRate = stop == null ? new BigDecimal("-2") : stop;
        if (targetRate.signum() <= 0 || stopRate.signum() >= 0) throw invalid("Invalid target/stop rates");
        ClosingRecommendationRun run = runs.findById(runId).orElseThrow(() -> invalid("Unknown evaluation run"));
        if (!"FORWARD".equals(run.getExecutionMode()) || run.getCompletedAt() == null)
            throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.CONFLICT,
                    "Only completed forward runs can be observed");
        // run에 저장된 평가 응답 스냅샷(당시 그대로의 기록)에서 후보 목록을 읽고, 측정일은 다음 거래일로 정해요.
        GenerateResponse snapshot = snapshot(run);
        LocalDate next = calendar.nextTradingDay(run.getRecommendationDate());
        List<CandidateEvaluationResponse> candidates = snapshot.evaluations() == null
                ? List.of() : snapshot.evaluations();
        Map<String, Stock> stockByCode = stocks.findByStockCodeIn(candidates.stream()
                .map(CandidateEvaluationResponse::stockCode).distinct().toList()).stream()
                .collect(Collectors.toMap(Stock::getStockCode, value -> value));
        List<ClosingCandidateObservation> saved = new ArrayList<>();
        // 처리: 후보마다 기존 관측 행을 찾고, 없으면 평가 당시 값(처분, 사유, 기준가, 점수)으로 새로 만들어요.
        for (CandidateEvaluationResponse candidate : candidates) {
            Stock stock = stockByCode.get(candidate.stockCode());
            if (stock == null) continue;
            ClosingCandidateObservation row = observations
                    .findByRunIdAndStockIdAndCandidateSource(runId, stock.getId(), candidate.candidateSource())
                    .orElseGet(() -> new ClosingCandidateObservation(run, stock, candidate.candidateSource(),
                            candidate.disposition(), candidate.decisionReason(), candidate.observedAt(),
                            candidate.referencePrice(), candidate.finalScore(), next, targetRate, stopRate));
            // 이미 저장된 행과 목표/손절 값이 다르면 표본이 섞이므로 409 충돌로 막아요.
            try { row.verifyThresholds(targetRate, stopRate); }
            catch (IllegalStateException error) {
                throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.CONFLICT, error.getMessage());
            }
            evaluate(row, run, calendar.now(), targetRate, stopRate);
            saved.add(observations.save(row));
        }
        // 결과: 이번에 저장된 관측 행으로 요약 보고서를 만들어요.
        return report(runId, saved);
    }

    /**
     * 이미 저장된 관측 행을 읽기 전용으로 조회해요.
     * 받는 것: run ID. 돌려주는 것: 처분·ID 순으로 정렬된 관측 Report.
     */
    @Transactional(readOnly = true)
    public Report list(Long runId) {
        if (runId == null) throw invalid("Evaluation run is required");
        return report(runId, observations.findByRunIdOrderByDispositionAscIdAsc(runId));
    }

    /**
     * 관측 행 1개의 진입·청산·상태를 지금 시각 기준으로 계산해 행에 기록해요.
     * 받는 것: 관측 행, run, 현재 시각, 목표·손절 수익률(%).
     * 돌려주는 것: 없음. 행의 상태(PENDING/IN_PROGRESS/DATA_MISSING/DATA_INCOMPLETE/COMPLETED)와 결과가 바뀌어요.
     */
    private void evaluate(ClosingCandidateObservation row, ClosingRecommendationRun run, Instant now,
            BigDecimal target, BigDecimal stop) {
        // 진입 준비: 구버전 run은 평가 완료 시각을 5분 단위로 올린 첫 확정 5분봉 시가로 진입해요.
        Instant recommendationClose = calendar.close(run.getRecommendationDate());
        Instant entryStart = ceilFiveMinutes(run.getCompletedAt());
        StockCandle entry = ClosingPaperEntryService.usesObservedEntry(run) ? null : candles
                .findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        row.getStock().getId(), "5M", entryStart, recommendationClose)
                .stream().filter(StockCandle::isFinalCandle).filter(this::validPrices).findFirst().orElse(null);
        // v9 계열 run은 ClosingPaperEntryService가 실제 관측한 가상 진입가만 써요(5분봉으로 대신 만들지 않아요).
        var captured = ClosingPaperEntryService.usesObservedEntry(run) ? paperEntries.find(run.getId(), row.getStock().getStockCode()) : null;
        BigDecimal entryPrice = captured != null ? captured.price() : entry == null ? null : entry.getOpen();
        Instant entryAt = captured != null ? captured.at() : entry == null ? null : entry.getStartTime();
        // 청산 준비: 다음 거래일 정규장의 확정·정상 가격·5분 정렬 봉만 시간순으로 모아요.
        Instant open = calendar.open(row.getExpectedSessionDate());
        Instant close = calendar.close(row.getExpectedSessionDate());
        List<StockCandle> exitSeries = candles
                .findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        row.getStock().getId(), "5M", open, close.plusSeconds(1))
                .stream().filter(StockCandle::isFinalCandle).filter(this::validPrices)
                .filter(value -> !value.getStartTime().isBefore(open) && value.getStartTime().isBefore(close))
                .filter(value -> Duration.between(open, value.getStartTime()).getSeconds() % 300 == 0)
                .sorted(Comparator.comparing(StockCandle::getStartTime)).toList();
        // Same rule as OvernightPerformanceService: 09:00..15:15 plus the 15:25 closing-print bar.
        List<String> missing = new ArrayList<>(NextSessionCoverage.missing(open, close, now, exitSeries).stream()
                .map(Instant::toString).toList());
        if (entryPrice == null) missing.add(captured == null ? "ENTRY_AFTER_RUN_COMPLETION" : captured.status());
        // 상태 판정: 장 시작 전 PENDING, 확정 여유 시각 전 IN_PROGRESS, 진입가나 봉이 없으면 DATA_MISSING,
        // 필수 봉이 빠지면 DATA_INCOMPLETE, 모두 있으면 COMPLETED예요.
        OvernightPerformanceStatus status = now.isBefore(open) ? OvernightPerformanceStatus.PENDING
                : now.isBefore(NextSessionCoverage.settledAt(close)) ? OvernightPerformanceStatus.IN_PROGRESS
                : entryPrice == null || exitSeries.isEmpty() ? OvernightPerformanceStatus.DATA_MISSING
                : missing.isEmpty() ? OvernightPerformanceStatus.COMPLETED : OvernightPerformanceStatus.DATA_INCOMPLETE;
        // 처리: 완료된 경우에만 목표/손절/종가 청산을 시뮬레이션해요. 비용이 적용되지 않았으면 순수익률은 비워요.
        OvernightExecutionSimulator.ExecutionResult result = status == OvernightPerformanceStatus.COMPLETED
                ? execution.targetOrStop(entryPrice, exitSeries, target, stop) : null;
        ClosingCandidateObservation.OvernightExecutionResult stored = result == null ? null
                : new ClosingCandidateObservation.OvernightExecutionResult(result.exitAt(),
                        result.exitReferencePrice(), result.grossReturnRate(),
                        result.costsApplied() ? result.netReturnRate() : null,
                        result.exitReason(), result.ambiguous(), result.costsApplied());
        // 결과: 상태, 빠진 구간 JSON, 진입·청산 결과, 비용 가정을 행에 기록해요.
        row.observe(now, status, json(missing), entryAt, entryPrice, stored, execution.costAssumption());
    }

    /**
     * SELECTED before WATCH, then higher final score; read from the immutable run snapshot.
     * 하는 일: 다음날 관측용 실시간 구독(OVERNIGHT)에 넣을 종목을 골라요.
     * 받는 것: run, 최대 종목 수. 돌려주는 것: 종목 코드 목록(완료된 FORWARD run이 아니거나 limit이 0 이하면 빈 목록).
     */
    public List<String> subscriptionCandidates(ClosingRecommendationRun run, int limit) {
        if (limit <= 0 || !"FORWARD".equals(run.getExecutionMode()) || run.getCompletedAt() == null) return List.of();
        List<CandidateEvaluationResponse> evaluations = snapshot(run).evaluations();
        if (evaluations == null) return List.of();
        return evaluations.stream()
                .filter(value -> "SELECTED".equals(value.disposition()) || "WATCH".equals(value.disposition()))
                .sorted(Comparator.comparing((CandidateEvaluationResponse value) -> !"SELECTED".equals(value.disposition()))
                        .thenComparing(CandidateEvaluationResponse::finalScore,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .map(CandidateEvaluationResponse::stockCode).distinct().limit(limit).toList();
    }

    /**
     * run에 저장된 평가 응답 스냅샷 JSON을 읽어 응답 객체로 되살려요.
     * 받는 것: run. 돌려주는 것: run 정보가 붙은 GenerateResponse. 읽기에 실패하면 IllegalStateException.
     */
    private GenerateResponse snapshot(ClosingRecommendationRun run) {
        try { return mapper.readValue(run.getResponseSnapshot(), GenerateResponse.class).withRun(run); }
        catch (Exception error) { throw new IllegalStateException("Failed to read recommendation audit", error); }
    }

    /**
     * 관측 행 목록으로 요약 보고서를 만들어요.
     * 받는 것: run ID, 관측 행 목록. 돌려주는 것: 전체·SELECTED·비선정·완료 건수와 행 목록이 담긴 Report.
     */
    private Report report(Long runId, List<ClosingCandidateObservation> rows) {
        List<Row> values = rows.stream().map(Row::from).toList();
        return new Report(runId, values.size(),
                (int) values.stream().filter(value -> "SELECTED".equals(value.disposition())).count(),
                (int) values.stream().filter(value -> !"SELECTED".equals(value.disposition())).count(),
                (int) values.stream().filter(value -> "COMPLETED".equals(value.status())).count(), values);
    }

    /**
     * 빠진 구간 목록 등 값을 JSON 문자열로 바꿔요.
     * 받는 것: 저장할 값. 돌려주는 것: JSON 문자열.
     */
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Failed to preserve observation gaps", error); }
    }

    /**
     * 시각을 다음 5분 경계로 올려요(정각이면 그대로).
     * 받는 것: 시각. 돌려주는 것: 5분 단위로 올린 시각.
     */
    private Instant ceilFiveMinutes(Instant value) {
        return Instant.ofEpochSecond(Math.floorDiv(value.getEpochSecond() + 299, 300) * 300);
    }

    /**
     * 봉 가격이 모두 있고 저가가 양수이며 고가·저가가 시가·종가를 감싸는지 확인해요.
     * 받는 것: 5분봉. 돌려주는 것: 정상이면 true.
     */
    private boolean validPrices(StockCandle value) {
        return value.getOpen() != null && value.getHigh() != null && value.getLow() != null
                && value.getClose() != null && value.getLow().signum() > 0
                && value.getHigh().compareTo(value.getOpen().max(value.getClose())) >= 0
                && value.getLow().compareTo(value.getOpen().min(value.getClose())) <= 0;
    }

    /**
     * 잘못된 요청(400) 예외를 만들어요.
     * 받는 것: 메시지. 돌려주는 것: ApplicationException.
     */
    private ApplicationException invalid(String message) {
        return new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST, message);
    }

    /** 관측 요약 보고서예요. run ID, 후보 수, SELECTED 수, 비선정 수, 완료 수, 행 목록을 담아요. */
    public record Report(Long runId, int candidates, int selected, int unselected, int completed, List<Row> rows) { }
    /** API로 내보내는 관측 행 1개예요. 진입·청산 가격, 총·순수익률, 청산 사유, 모호 여부, 빠진 구간을 담아요. */
    public record Row(Long id, String stockCode, String stockName, String candidateSource, String disposition,
            String decisionReason, BigDecimal finalScore, String status, LocalDate expectedSessionDate,
            Instant entryAt, BigDecimal entryPrice, Instant exitAt, BigDecimal exitPrice,
            BigDecimal grossReturnRate, BigDecimal netReturnRate, String exitReason,
            boolean executionAmbiguous, String costStatus, String missingIntervals) {
        /**
         * 저장된 관측 엔티티를 응답 행으로 옮겨요.
         * 받는 것: 관측 엔티티. 돌려주는 것: Row.
         */
        static Row from(ClosingCandidateObservation value) {
            return new Row(value.getId(), value.getStock().getStockCode(), value.getStock().getStockName(),
                    value.getCandidateSource(), value.getDisposition(), value.getDecisionReason(),
                    value.getFinalScore(), value.getStatus().name(), value.getExpectedSessionDate(),
                    value.getEntryAt(), value.getEntryPrice(), value.getExitAt(), value.getExitPrice(),
                    value.getGrossReturnRate(), value.getNetReturnRate(), value.getExitReason(),
                    value.isExecutionAmbiguous(), value.getCostStatus(), value.getMissingIntervals());
        }
    }
}
