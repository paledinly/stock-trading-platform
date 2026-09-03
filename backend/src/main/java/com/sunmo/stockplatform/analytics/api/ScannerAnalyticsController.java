package com.sunmo.stockplatform.analytics.api;

import com.sunmo.stockplatform.analytics.application.ScannerAnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1")
public class ScannerAnalyticsController {
    private final ScannerAnalyticsService service;

    public ScannerAnalyticsController(ScannerAnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/scanner-detections/{id}/performance")
    public PerformanceDtos.PerformanceResponse performance(@PathVariable long id) {
        return service.performance(id);
    }

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
