package com.sunmo.stockplatform.marketwide.api;

import com.sunmo.stockplatform.marketwide.application.MarketWideScannerService;
import com.sunmo.stockplatform.marketwide.application.MarketWideScanCoordinator;
import com.sunmo.stockplatform.marketwide.application.MarketWideDiagnostics;
import com.sunmo.stockplatform.marketwide.application.MarketCoverageService;
import com.sunmo.stockplatform.stock.domain.Market;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDate;

@Validated
@RestController
@RequestMapping("/api/v1/market-wide")
public class MarketWideScannerController {
    private final MarketWideScanCoordinator scans;
    private final MarketWideDiagnostics diagnostics;
    private final MarketCoverageService coverage;
    private final com.sunmo.stockplatform.marketwide.application.BroadEnrichmentQueue enrichment;

    public MarketWideScannerController(MarketWideScanCoordinator scans, MarketWideDiagnostics diagnostics,
            MarketCoverageService coverage, com.sunmo.stockplatform.marketwide.application.BroadEnrichmentQueue enrichment) {
        this.scans = scans;
        this.diagnostics = diagnostics;
        this.coverage = coverage;
        this.enrichment = enrichment;
    }

    @GetMapping("/scan")
    public MarketWideDtos.BroadScanResponse scan(@RequestParam(required = false) Market market,
            @RequestParam(defaultValue = "40") @Min(1) @Max(120) int limit,
            @RequestParam(defaultValue = "12") @Min(1) @Max(41) int candidates,
            @RequestParam(defaultValue = "false") boolean includeEtf) {
        return scans.manual(market, limit, candidates, includeEtf);
    }

    @GetMapping("/status")
    public MarketWideDiagnostics.Snapshot status() {
        return diagnostics.snapshot(enrichment.snapshot());
    }

    @GetMapping("/coverage")
    public MarketWideDtos.CoverageResponse coverage(@RequestParam(required = false) LocalDate date) {
        return coverage.coverage(date);
    }
}
