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

@Service
public class ClosingCandidateObservationService {
    private final ClosingRecommendationRunRepository runs;
    private final ClosingCandidateObservationRepository observations;
    private final StockRepository stocks;
    private final StockCandleRepository candles;
    private final ClosingTradingCalendar calendar;
    private final OvernightExecutionSimulator execution;
    private final ObjectMapper mapper;

    public ClosingCandidateObservationService(ClosingRecommendationRunRepository runs,
            ClosingCandidateObservationRepository observations, StockRepository stocks,
            StockCandleRepository candles, ClosingTradingCalendar calendar,
            OvernightExecutionSimulator execution, ObjectMapper mapper) {
        this.runs = runs;
        this.observations = observations;
        this.stocks = stocks;
        this.candles = candles;
        this.calendar = calendar;
        this.execution = execution;
        this.mapper = mapper;
    }

    @Transactional
    public Report track(Long runId, BigDecimal target, BigDecimal stop) {
        if (runId == null) throw invalid("Evaluation run is required");
        BigDecimal targetRate = target == null ? new BigDecimal("3") : target;
        BigDecimal stopRate = stop == null ? new BigDecimal("-2") : stop;
        if (targetRate.signum() <= 0 || stopRate.signum() >= 0) throw invalid("Invalid target/stop rates");
        ClosingRecommendationRun run = runs.findById(runId).orElseThrow(() -> invalid("Unknown evaluation run"));
        if (!"FORWARD".equals(run.getExecutionMode()) || run.getCompletedAt() == null)
            throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.CONFLICT,
                    "Only completed forward runs can be observed");
        GenerateResponse snapshot = snapshot(run);
        LocalDate next = calendar.nextTradingDay(run.getRecommendationDate());
        List<CandidateEvaluationResponse> candidates = snapshot.evaluations() == null
                ? List.of() : snapshot.evaluations();
        Map<String, Stock> stockByCode = stocks.findByStockCodeIn(candidates.stream()
                .map(CandidateEvaluationResponse::stockCode).distinct().toList()).stream()
                .collect(Collectors.toMap(Stock::getStockCode, value -> value));
        List<ClosingCandidateObservation> saved = new ArrayList<>();
        for (CandidateEvaluationResponse candidate : candidates) {
            Stock stock = stockByCode.get(candidate.stockCode());
            if (stock == null) continue;
            ClosingCandidateObservation row = observations
                    .findByRunIdAndStockIdAndCandidateSource(runId, stock.getId(), candidate.candidateSource())
                    .orElseGet(() -> new ClosingCandidateObservation(run, stock, candidate.candidateSource(),
                            candidate.disposition(), candidate.decisionReason(), candidate.observedAt(),
                            candidate.referencePrice(), candidate.finalScore(), next, targetRate, stopRate));
            try { row.verifyThresholds(targetRate, stopRate); }
            catch (IllegalStateException error) {
                throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.CONFLICT, error.getMessage());
            }
            evaluate(row, run, calendar.now(), targetRate, stopRate);
            saved.add(observations.save(row));
        }
        return report(runId, saved);
    }

    @Transactional(readOnly = true)
    public Report list(Long runId) {
        if (runId == null) throw invalid("Evaluation run is required");
        return report(runId, observations.findByRunIdOrderByDispositionAscIdAsc(runId));
    }

    private void evaluate(ClosingCandidateObservation row, ClosingRecommendationRun run, Instant now,
            BigDecimal target, BigDecimal stop) {
        Instant recommendationClose = calendar.close(run.getRecommendationDate());
        Instant entryStart = ceilFiveMinutes(run.getCompletedAt());
        StockCandle entry = candles
                .findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        row.getStock().getId(), "5M", entryStart, recommendationClose)
                .stream().filter(StockCandle::isFinalCandle).filter(this::validPrices).findFirst().orElse(null);
        Instant open = calendar.open(row.getExpectedSessionDate());
        Instant close = calendar.close(row.getExpectedSessionDate());
        List<StockCandle> exitSeries = candles
                .findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        row.getStock().getId(), "5M", open, close.plusSeconds(1))
                .stream().filter(StockCandle::isFinalCandle).filter(this::validPrices)
                .filter(value -> !value.getStartTime().isBefore(open) && value.getStartTime().isBefore(close))
                .sorted(Comparator.comparing(StockCandle::getStartTime)).toList();
        Map<Instant, Long> counts = exitSeries.stream()
                .collect(Collectors.groupingBy(StockCandle::getStartTime, Collectors.counting()));
        List<String> missing = new ArrayList<>();
        for (Instant at = open; at.isBefore(close) && !at.plusSeconds(300).isAfter(now); at = at.plusSeconds(300))
            if (counts.getOrDefault(at, 0L) != 1) missing.add(at.toString());
        if (entry == null) missing.add("ENTRY_AFTER_RUN_COMPLETION");
        OvernightPerformanceStatus status = now.isBefore(open) ? OvernightPerformanceStatus.PENDING
                : now.isBefore(close) ? OvernightPerformanceStatus.IN_PROGRESS
                : entry == null || exitSeries.isEmpty() ? OvernightPerformanceStatus.DATA_MISSING
                : missing.isEmpty() ? OvernightPerformanceStatus.COMPLETED : OvernightPerformanceStatus.DATA_INCOMPLETE;
        OvernightExecutionSimulator.ExecutionResult result = status == OvernightPerformanceStatus.COMPLETED
                ? execution.targetOrStop(entry.getOpen(), exitSeries, target, stop) : null;
        ClosingCandidateObservation.OvernightExecutionResult stored = result == null ? null
                : new ClosingCandidateObservation.OvernightExecutionResult(result.exitAt(),
                        result.exitReferencePrice(), result.grossReturnRate(),
                        result.costsApplied() ? result.netReturnRate() : null,
                        result.exitReason(), result.ambiguous(), result.costsApplied());
        row.observe(now, status, json(missing), entry == null ? null : entry.getStartTime(),
                entry == null ? null : entry.getOpen(), stored, execution.costAssumption());
    }

    private GenerateResponse snapshot(ClosingRecommendationRun run) {
        try { return mapper.readValue(run.getResponseSnapshot(), GenerateResponse.class).withRun(run); }
        catch (Exception error) { throw new IllegalStateException("Failed to read recommendation audit", error); }
    }

    private Report report(Long runId, List<ClosingCandidateObservation> rows) {
        List<Row> values = rows.stream().map(Row::from).toList();
        return new Report(runId, values.size(),
                (int) values.stream().filter(value -> "SELECTED".equals(value.disposition())).count(),
                (int) values.stream().filter(value -> !"SELECTED".equals(value.disposition())).count(),
                (int) values.stream().filter(value -> "COMPLETED".equals(value.status())).count(), values);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Failed to preserve observation gaps", error); }
    }

    private Instant ceilFiveMinutes(Instant value) {
        return Instant.ofEpochSecond(Math.floorDiv(value.getEpochSecond() + 299, 300) * 300);
    }

    private boolean validPrices(StockCandle value) {
        return value.getOpen() != null && value.getHigh() != null && value.getLow() != null
                && value.getClose() != null && value.getLow().signum() > 0
                && value.getHigh().compareTo(value.getOpen().max(value.getClose())) >= 0
                && value.getLow().compareTo(value.getOpen().min(value.getClose())) <= 0;
    }

    private ApplicationException invalid(String message) {
        return new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST, message);
    }

    public record Report(Long runId, int candidates, int selected, int unselected, int completed, List<Row> rows) { }
    public record Row(Long id, String stockCode, String stockName, String candidateSource, String disposition,
            String decisionReason, BigDecimal finalScore, String status, LocalDate expectedSessionDate,
            Instant entryAt, BigDecimal entryPrice, Instant exitAt, BigDecimal exitPrice,
            BigDecimal grossReturnRate, BigDecimal netReturnRate, String exitReason,
            boolean executionAmbiguous, String costStatus, String missingIntervals) {
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
