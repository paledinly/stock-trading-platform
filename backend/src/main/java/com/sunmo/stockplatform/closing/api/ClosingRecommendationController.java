package com.sunmo.stockplatform.closing.api;

import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.GenerateResponse;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.DecisionEvaluationResponse;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.OvernightBacktestResponse;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.OvernightPerformanceResponse;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.OvernightPositionDecisionResponse;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.TrackPerformanceResponse;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.RecommendationResponse;
import com.sunmo.stockplatform.closing.application.ClosingRecommendationService;
import com.sunmo.stockplatform.closing.application.OvernightBacktestService;
import com.sunmo.stockplatform.closing.application.OvernightPerformanceService;
import com.sunmo.stockplatform.closing.application.OvernightPositionDecisionService;
import com.sunmo.stockplatform.closing.application.AccountPerformanceCalculator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/closing-recommendations")
public class ClosingRecommendationController {
    private final ClosingRecommendationService service;
    private final OvernightPerformanceService performanceService;
    private final OvernightBacktestService backtestService;
    private final OvernightPositionDecisionService decisionService;
    private final AccountPerformanceCalculator accountPerformance;

    public ClosingRecommendationController(ClosingRecommendationService service,
            OvernightPerformanceService performanceService, OvernightBacktestService backtestService,
            OvernightPositionDecisionService decisionService, AccountPerformanceCalculator accountPerformance) {
        this.service = service;
        this.performanceService = performanceService;
        this.backtestService = backtestService;
        this.decisionService = decisionService;
        this.accountPerformance = accountPerformance;
    }

    @PostMapping("/generate")
    public GenerateResponse generate(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "10") @Min(1) @Max(30) int limit,
            @RequestParam(defaultValue = "35") @PositiveOrZero BigDecimal minOpportunity,
            @RequestParam(defaultValue = "65") @PositiveOrZero BigDecimal maxRisk,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestKey) {
        return service.generate(date, limit, minOpportunity, maxRisk, requestKey);
    }

    @GetMapping
    public List<RecommendationResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long runId) {
        return service.list(date, runId);
    }

    @GetMapping("/runs")
    public List<ClosingRecommendationDtos.RunResponse> runs(@RequestParam(required = false) LocalDate date) {
        return service.history(date);
    }

    @GetMapping("/evaluation")
    public org.springframework.http.ResponseEntity<GenerateResponse> evaluation(@RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) Long runId) {
        GenerateResponse response = service.evaluation(date, runId);
        return response == null ? org.springframework.http.ResponseEntity.noContent().build()
                : org.springframework.http.ResponseEntity.ok(response);
    }

    @PostMapping("/performance/track")
    public TrackPerformanceResponse trackPerformance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "3") BigDecimal targetRate,
            @RequestParam(defaultValue = "-2") BigDecimal stopRate,
            @RequestParam(required = false) Long runId) {
        return performanceService.track(service.runDate(date, runId), targetRate, stopRate, runId);
    }

    @GetMapping("/performance")
    public List<OvernightPerformanceResponse> performances(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long runId) {
        return performanceService.list(service.runDate(date, runId), runId);
    }

    @PostMapping("/decisions/evaluate")
    public DecisionEvaluationResponse evaluateDecisions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "3") BigDecimal targetRate,
            @RequestParam(defaultValue = "-2") BigDecimal stopRate,
            @RequestParam(required = false) Long runId) {
        return decisionService.evaluate(service.runDate(date, runId), targetRate, stopRate, runId);
    }

    @GetMapping("/decisions")
    public List<OvernightPositionDecisionResponse> decisions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long runId) {
        return decisionService.list(service.runDate(date, runId), runId);
    }

    @GetMapping("/backtest")
    public OvernightBacktestResponse backtest(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "10") @Min(1) @Max(30) int limit,
            @RequestParam(defaultValue = "35") @PositiveOrZero BigDecimal minOpportunity,
            @RequestParam(defaultValue = "65") @PositiveOrZero BigDecimal maxRisk,
            @RequestParam(defaultValue = "3") BigDecimal targetRate,
            @RequestParam(defaultValue = "-2") BigDecimal stopRate) {
        return backtestService.run(from, to, limit, minOpportunity, maxRisk, targetRate, stopRate);
    }

    @GetMapping("/account-performance")
    public AccountPerformanceCalculator.Report accountPerformance() {
        return accountPerformance.unavailable();
    }
}
