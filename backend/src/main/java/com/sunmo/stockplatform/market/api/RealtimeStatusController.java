package com.sunmo.stockplatform.market.api;

import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market/realtime")
public class RealtimeStatusController {
    private final RealtimeDiagnostics diagnostics;

    public RealtimeStatusController(RealtimeDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    @GetMapping("/status")
    public RealtimeDiagnostics.Snapshot status() {
        return diagnostics.snapshot();
    }
}
