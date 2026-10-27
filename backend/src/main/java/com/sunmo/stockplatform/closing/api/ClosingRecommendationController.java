/*
 * 장마감 추천 기능의 REST API(웹이 URL로 요청하고 JSON으로 답을 받는 통로)를 모아 둔 컨트롤러예요.
 * 평가 실행, 추천·실행 이력 조회, 다음 날 성과·보유 판단 갱신과 조회, 백테스트, 전략 분석, 후보 관찰 기록을 제공해요.
 * 웹의 장마감 추천 화면(ClosingRecommendationApp)이 호출해요. 실제 주문 API는 없고 모두 가상 계산이에요.
 */
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
import com.sunmo.stockplatform.closing.application.ClosingStrategyAnalyticsService;
import com.sunmo.stockplatform.closing.application.ClosingCandidateObservationService;
import com.sunmo.stockplatform.closing.application.MarketSessionHours;
import com.sunmo.stockplatform.closing.application.OfficialTradingDays;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * /api/v1/closing-recommendations 아래 요청을 받아 알맞은 서비스로 넘기는 컨트롤러(웹 요청을 받아 서비스로 연결하는 창구)예요.
 * 식당 홀 직원처럼 주문(요청)을 받아 주방(서비스)에 전달하고 음식(응답)을 그대로 가져다줘요. 계산은 직접 하지 않아요.
 * 날짜를 비우면 보통 오늘(서비스 기준), runId를 주면 그 실행 기준으로 조회해요.
 */
@Validated
@RestController
@RequestMapping("/api/v1/closing-recommendations")
public class ClosingRecommendationController {
    private final ClosingRecommendationService service;
    private final OvernightPerformanceService performanceService;
    private final OvernightBacktestService backtestService;
    private final OvernightPositionDecisionService decisionService;
    private final AccountPerformanceCalculator accountPerformance;
    private final ClosingStrategyAnalyticsService strategyAnalytics;
    private final ClosingCandidateObservationService candidateObservations;
    private final OfficialTradingDays officialTradingDays;

    /** 하는 일: 각 기능 서비스를 받아 보관해요(스프링이 자동으로 넣어 줘요). 받는 것: 서비스들. 돌려주는 것: 컨트롤러 객체예요. */
    public ClosingRecommendationController(ClosingRecommendationService service,
            OvernightPerformanceService performanceService, OvernightBacktestService backtestService,
            OvernightPositionDecisionService decisionService, AccountPerformanceCalculator accountPerformance,
            ClosingStrategyAnalyticsService strategyAnalytics,
            ClosingCandidateObservationService candidateObservations, OfficialTradingDays officialTradingDays) {
        this.service = service;
        this.performanceService = performanceService;
        this.backtestService = backtestService;
        this.decisionService = decisionService;
        this.accountPerformance = accountPerformance;
        this.strategyAnalytics = strategyAnalytics;
        this.candidateObservations = candidateObservations;
        this.officialTradingDays = officialTradingDays;
    }

    /**
     * 하는 일: GET /trading-calendar. 공식 거래일 달력의 현재 상태(얼마나 확보됐는지 등)를 돌려줘요.
     * 받는 것: 없어요. 돌려주는 것: OfficialTradingDays.Status예요.
     */
    @GetMapping("/trading-calendar")
    public OfficialTradingDays.Status tradingCalendar() {
        return officialTradingDays.status();
    }

    /** 그날 정규장 시간이에요. special은 수능일처럼 설정으로 바뀐 날이면 true예요. */
    public record SessionHours(LocalDate date, String open, String close, boolean special) { }

    /**
     * 하는 일: GET /session-hours. 날짜(없으면 오늘)의 정규장 개장·마감 시각을 돌려줘요(MarketSessionHours 설정 기준).
     * 화면의 장 구간 표시·다음 작업 시각을 수능일 같은 특별 일정에 맞추는 데 써요.
     */
    @GetMapping("/session-hours")
    public SessionHours sessionHours(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date == null ? LocalDate.now(MarketSessionHours.ZONE) : date;
        return new SessionHours(target, MarketSessionHours.open(target).toString().substring(0, 5),
                MarketSessionHours.close(target).toString().substring(0, 5),
                MarketSessionHours.specialSessions().containsKey(target));
    }

    /**
     * 하는 일: POST /generate. 지정 날짜의 장마감 후보 평가를 실행하고 추천을 저장해요.
     * 오늘이고 15:20 진입 마감 전이면 FORWARD, 그 밖에는 REPLAY 모드로 돌고, 15:15 입력 마감 전에는 거부돼요.
     * 받는 것: 날짜(선택), 최대 추천 수(1~30), 최소 기회 점수, 최대 위험 점수, Idempotency-Key 헤더(같은 요청 중복 방지 키, 선택)예요.
     * 돌려주는 것: 평가 전체 결과(GenerateResponse)예요.
     */
    @PostMapping("/generate")
    public GenerateResponse generate(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "10") @Min(1) @Max(30) int limit,
            @RequestParam(defaultValue = "35") @PositiveOrZero BigDecimal minOpportunity,
            @RequestParam(defaultValue = "65") @PositiveOrZero BigDecimal maxRisk,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestKey) {
        return service.generate(date, limit, minOpportunity, maxRisk, requestKey);
    }

    /**
     * 하는 일: GET /. 날짜 또는 실행의 추천 명단을 순위 순으로 돌려줘요(날짜만 주면 FORWARD 실행 우선).
     * 받는 것: 날짜(선택), runId(선택)예요. 돌려주는 것: 추천 목록이에요.
     */
    @GetMapping
    public List<RecommendationResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long runId) {
        return service.list(date, runId);
    }

    /**
     * 하는 일: GET /runs. 그날의 평가 실행 이력을 최신순으로 돌려줘요(날짜를 비우면 오늘).
     * 받는 것: 날짜(선택)예요. 다른 엔드포인트와 달리 @DateTimeFormat이 없지만, 웹은 ISO 날짜로 호출해요.
     * 돌려주는 것: 실행 목록(RunResponse)이에요.
     */
    @GetMapping("/runs")
    public List<ClosingRecommendationDtos.RunResponse> runs(@RequestParam(required = false) LocalDate date) {
        return service.history(date);
    }

    /**
     * 하는 일: GET /evaluation. 저장된 평가 결과 원본(실행 당시 응답 스냅샷)을 다시 읽어 돌려줘요.
     * 받는 것: 날짜(선택), runId(선택)예요. runId가 없으면 그날 최신 FORWARD 실행, 없으면 최신 실행을 써요.
     * 돌려주는 것: GenerateResponse, 결과가 없으면 204 No Content예요.
     */
    @GetMapping("/evaluation")
    public org.springframework.http.ResponseEntity<GenerateResponse> evaluation(@RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) Long runId) {
        GenerateResponse response = service.evaluation(date, runId);
        return response == null ? org.springframework.http.ResponseEntity.noContent().build()
                : org.springframework.http.ResponseEntity.ok(response);
    }

    /**
     * 하는 일: POST /performance/track. 추천들의 다음 거래일 성과를 지금까지의 봉으로 다시 계산해 저장해요.
     * 받는 것: 날짜(선택), 목표 비율(기본 3%), 손절 비율(기본 -2%), runId(선택)예요.
     * 돌려주는 것: 성과 갱신 요약(TrackPerformanceResponse)이에요.
     */
    @PostMapping("/performance/track")
    public TrackPerformanceResponse trackPerformance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "3") BigDecimal targetRate,
            @RequestParam(defaultValue = "-2") BigDecimal stopRate,
            @RequestParam(required = false) Long runId) {
        return performanceService.track(service.runDate(date, runId), targetRate, stopRate, runId);
    }

    /**
     * 하는 일: GET /performance. 저장된 다음 날 성과를 돌려줘요(새로 계산하지 않아요).
     * 받는 것: 날짜(선택), runId(선택)예요. 돌려주는 것: 성과 목록이에요.
     */
    @GetMapping("/performance")
    public List<OvernightPerformanceResponse> performances(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long runId) {
        return performanceService.list(service.runDate(date, runId), runId);
    }

    /**
     * 하는 일: POST /decisions/evaluate. 추천 종목마다 현재 시세로 보유/익절/경고/손절 판단을 새로 내려 저장해요.
     * 받는 것: 날짜(선택), 목표 비율(기본 3%), 손절 비율(기본 -2%), runId(선택)예요.
     * 돌려주는 것: 판단 요약(DecisionEvaluationResponse)이에요.
     */
    @PostMapping("/decisions/evaluate")
    public DecisionEvaluationResponse evaluateDecisions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "3") BigDecimal targetRate,
            @RequestParam(defaultValue = "-2") BigDecimal stopRate,
            @RequestParam(required = false) Long runId) {
        return decisionService.evaluate(service.runDate(date, runId), targetRate, stopRate, runId);
    }

    /**
     * 하는 일: GET /decisions. 추천마다 가장 최근 보유 판단을 돌려줘요.
     * 받는 것: 날짜(선택), runId(선택)예요. 돌려주는 것: 판단 목록이에요.
     */
    @GetMapping("/decisions")
    public List<OvernightPositionDecisionResponse> decisions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long runId) {
        return decisionService.list(service.runDate(date, runId), runId);
    }

    /**
     * 하는 일: GET /backtest. 과거 기간에 대해 같은 규칙으로 가상 추천·다음 날 성과를 다시 계산해 요약해요. 저장된 공식 결과와는 별개예요.
     * 받는 것: 시작·종료일(선택), 최대 추천 수(1~30), 최소 기회·최대 위험 점수, 목표·손절 비율이에요.
     * 돌려주는 것: 백테스트 결과(OvernightBacktestResponse)예요.
     */
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

    /**
     * 하는 일: GET /account-performance. 계좌 단위 성과를 돌려주는 자리예요.
     * 받는 것: 없어요. 돌려주는 것: 항상 "사용 불가" 보고서(unavailable())예요.
     * ⚠️ 확인 필요: 계산기를 주입받았지만 실제 계산을 부르지 않고 항상 unavailable()만 돌려줘요.
     * 웹(ClosingRecommendationApp.tsx)은 이 API를 계속 호출하므로, 화면에는 늘 "사용 불가"만 보여요.
     */
    @GetMapping("/account-performance")
    public AccountPerformanceCalculator.Report accountPerformance() {
        return accountPerformance.unavailable();
    }

    /**
     * 하는 일: GET /strategy-analytics. 기간 안 공식(FORWARD, official-) 완료 성과만 모아 전략 성과를 분석해요(최대 730일).
     * 받는 것: 시작·종료일(선택)이에요. 돌려주는 것: 전략 분석 보고서예요. 표본이 적으면 수익성 검증으로 보면 안 돼요.
     */
    @GetMapping("/strategy-analytics")
    public ClosingStrategyAnalyticsService.StrategyAnalyticsReport strategyAnalytics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return strategyAnalytics.analyze(from, to);
    }

    /**
     * 하는 일: POST /candidate-observations/track. 한 실행의 SELECTED/WATCH 후보 전체에 대해 다음 날 가상 결과를 계산·저장해요.
     * 받는 것: runId(필수), 목표 비율(기본 3%), 손절 비율(기본 -2%)이에요. 같은 실행에서 비율을 바꾸면 409 충돌이 나요.
     * 돌려주는 것: 관찰 결과 보고서예요.
     */
    @PostMapping("/candidate-observations/track")
    public ClosingCandidateObservationService.Report trackCandidateObservations(
            @RequestParam Long runId,
            @RequestParam(defaultValue = "3") BigDecimal targetRate,
            @RequestParam(defaultValue = "-2") BigDecimal stopRate) {
        return candidateObservations.track(runId, targetRate, stopRate);
    }

    /**
     * 하는 일: GET /candidate-observations. 한 실행의 저장된 후보 관찰 기록을 돌려줘요.
     * 받는 것: runId(필수)예요. 돌려주는 것: 관찰 결과 보고서예요.
     */
    @GetMapping("/candidate-observations")
    public ClosingCandidateObservationService.Report candidateObservations(@RequestParam Long runId) {
        return candidateObservations.list(runId);
    }
}
