package com.sunmo.stockplatform.candle.api;

import com.sunmo.stockplatform.candle.application.AdjustedDailySweepService;
import com.sunmo.stockplatform.candle.application.AdjustedDailySweepService.SweepStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market-data/adjusted-daily")
public class AdjustedDailySweepController {
    private final AdjustedDailySweepService service;

    public AdjustedDailySweepController(AdjustedDailySweepService service) { this.service = service; }

    /** 202 when a background sweep started; 409 when one is running, KIS is disabled or the session guard applies. */
    @PostMapping("/sweep")
    public ResponseEntity<SweepStatus> sweep() {
        boolean started = service.start();
        return ResponseEntity.status(started ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT).body(service.status());
    }

    @GetMapping("/status")
    public SweepStatus status() { return service.status(); }
}
