package com.sunmo.stockplatform.candle.api;

import com.sunmo.stockplatform.candle.application.DailyCandleBackfillService;
import com.sunmo.stockplatform.candle.application.DailyCandleBackfillService.PrepareResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/closing-recommendations/daily-data")
public class DailyCandleBackfillController {
    private final DailyCandleBackfillService service;

    public DailyCandleBackfillController(DailyCandleBackfillService service) { this.service = service; }

    @PostMapping("/prepare")
    public PrepareResult prepare() { return service.prepare(); }
}
