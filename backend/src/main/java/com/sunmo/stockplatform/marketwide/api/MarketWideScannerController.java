package com.sunmo.stockplatform.marketwide.api;

import com.sunmo.stockplatform.marketwide.application.MarketWideScannerService;
import com.sunmo.stockplatform.marketwide.application.BroadSnapshotQueryService;
import com.sunmo.stockplatform.marketwide.application.PrecisionSubscriptionAllocator;
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
import java.time.ZoneId;
import java.util.List;
import jakarta.validation.constraints.Pattern;

@Validated
@RestController
@RequestMapping("/api/v1/market-wide")
public class MarketWideScannerController {
    private final MarketWideScanCoordinator scans;
    private final BroadSnapshotQueryService snapshots;
    private final PrecisionSubscriptionAllocator precision;
    private final MarketWideDiagnostics diagnostics;
    private final MarketCoverageService coverage;

    public MarketWideScannerController(MarketWideScanCoordinator scans, BroadSnapshotQueryService snapshots,
            PrecisionSubscriptionAllocator precision, MarketWideDiagnostics diagnostics,
            MarketCoverageService coverage) {
        this.scans = scans;
        this.snapshots = snapshots;
        this.precision = precision;
        this.diagnostics = diagnostics;
        this.coverage = coverage;
    }

    @GetMapping("/scan")
    public MarketWideDtos.BroadScanResponse scan(@RequestParam(required = false) Market market,
            @RequestParam(defaultValue = "40") @Min(1) @Max(120) int limit,
            @RequestParam(defaultValue = "12") @Min(1) @Max(30) int candidates,
            @RequestParam(defaultValue = "false") boolean includeEtf) {
        return scans.manual(market, limit, candidates, includeEtf);
    }

    @GetMapping("/snapshots")
    public List<MarketWideDtos.SnapshotResponse> snapshots(
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) @Pattern(regexp = "[A-Z0-9]{6,12}") String stockCode,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        LocalDate targetDate = date == null ? LocalDate.now(ZoneId.of("Asia/Seoul")) : date;
        return snapshots.history(targetDate, stockCode, limit);
    }

    @GetMapping("/precision")
    public PrecisionSubscriptionAllocator.Snapshot precision() {
        return precision.snapshot();
    }

    @GetMapping("/status")
    public MarketWideDiagnostics.Snapshot status() {
        return diagnostics.snapshot();
    }

    @GetMapping("/coverage")
    public MarketWideDtos.CoverageResponse coverage(@RequestParam(required = false) LocalDate date) {
        return coverage.coverage(date);
    }
}
