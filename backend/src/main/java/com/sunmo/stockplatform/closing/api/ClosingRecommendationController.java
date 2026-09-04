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

    public ClosingRecommendationController(ClosingRecommendationService service,
            OvernightPerformanceService performanceService, OvernightBacktestService backtestService,
            OvernightPositionDecisionService decisionService) {
        this.service = service;
        this.performanceService = performanceService;
        this.backtestService = backtestService;
        this.decisionService = decisionService;
    }

    @PostMapping("/generate")
    public GenerateResponse generate(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "10") @Min(1) @Max(30) int limit,
            @RequestParam(defaultValue = "35") @PositiveOrZero BigDecimal minOpportunity,
            @RequestParam(defaultValue = "65") @PositiveOrZero BigDecimal maxRisk) {
        return service.generate(date, limit, minOpportunity, maxRisk);
    }

    @GetMapping
    public List<RecommendationResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.list(date);
    }

    @PostMapping("/performance/track")
    public TrackPerformanceResponse trackPerformance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "3") BigDecimal targetRate,
            @RequestParam(defaultValue = "-2") BigDecimal stopRate) {
        return performanceService.track(date, targetRate, stopRate);
    }

    @GetMapping("/performance")
    public List<OvernightPerformanceResponse> performances(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return performanceService.list(date);
    }

    @PostMapping("/decisions/evaluate")
    public DecisionEvaluationResponse evaluateDecisions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "3") BigDecimal targetRate,
            @RequestParam(defaultValue = "-2") BigDecimal stopRate) {
        return decisionService.evaluate(date, targetRate, stopRate);
    }

    @GetMapping("/decisions")
    public List<OvernightPositionDecisionResponse> decisions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return decisionService.list(date);
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
}
