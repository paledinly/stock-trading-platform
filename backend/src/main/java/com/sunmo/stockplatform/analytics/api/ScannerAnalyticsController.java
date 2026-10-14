/*
 * 감지 성과와 기간 통계를 HTTP API(외부에서 서버 기능을 부르는 창구)로 내보내는 파일이에요.
 * 요청 값을 받아 ScannerAnalyticsService에 넘기고 결과를 그대로 돌려줘요.
 * 웹 화면의 스캐너 성과·분석 화면이 이 API를 불러요.
 */
package com.sunmo.stockplatform.analytics.api;

import com.sunmo.stockplatform.analytics.application.ScannerAnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 성과 조회 요청을 받는 컨트롤러(주소별 요청을 받아 담당자에게 넘기는 창구)예요.
 * 은행 창구 직원처럼 신청서를 받아 담당 부서(ScannerAnalyticsService)에 넘기고 결과를 건네줘요.
 */
@RestController
@RequestMapping("/api/v1")
public class ScannerAnalyticsController {
    private final ScannerAnalyticsService service;

    /** 받는 것: 통계 서비스. 돌려주는 것: 컨트롤러 객체예요. */
    public ScannerAnalyticsController(ScannerAnalyticsService service) {
        this.service = service;
    }

    /**
     * GET /api/v1/scanner-detections/{id}/performance: 감지 한 건의 성과 상세를 돌려줘요.
     * 받는 것: 감지 ID. 돌려주는 것: 성과 응답이에요(없으면 404).
     */
    @GetMapping("/scanner-detections/{id}/performance")
    public PerformanceDtos.PerformanceResponse performance(@PathVariable long id) {
        return service.performance(id);
    }

    /**
     * GET /api/v1/scanner-analytics: 기간 통계를 돌려줘요.
     * 받는 것: 설정 ID(선택), 기간 from·to, 목표 수익률(기본 3%), 손절 수익률(기본 -2%), 최소 표본 수(기본 20).
     * 돌려주는 것: 통계 응답이에요. 조건이 잘못되면 400 오류예요.
     */
    @GetMapping("/scanner-analytics")
    public PerformanceDtos.AnalyticsResponse analytics(@RequestParam(required = false) Long settingId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "3") BigDecimal targetRate,
            @RequestParam(defaultValue = "-2") BigDecimal stopRate,
            @RequestParam(defaultValue = "20") int minimumSampleSize) {
        return service.analytics(settingId, from, to, targetRate, stopRate, minimumSampleSize);
    }
}
