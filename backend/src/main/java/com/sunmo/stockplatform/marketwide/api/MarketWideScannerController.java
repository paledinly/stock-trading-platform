package com.sunmo.stockplatform.marketwide.api;

import com.sunmo.stockplatform.marketwide.application.MarketWideScannerService;
import com.sunmo.stockplatform.stock.domain.Market;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/market-wide")
public class MarketWideScannerController {
    private final MarketWideScannerService service;

    public MarketWideScannerController(MarketWideScannerService service) {
        this.service = service;
    }

    @GetMapping("/scan")
    public MarketWideDtos.BroadScanResponse scan(@RequestParam(required = false) Market market,
            @RequestParam(defaultValue = "40") @Min(1) @Max(120) int limit,
            @RequestParam(defaultValue = "12") @Min(1) @Max(30) int candidates,
            @RequestParam(defaultValue = "false") boolean includeEtf) {
        return service.scan(market, limit, candidates, includeEtf);
    }
}
