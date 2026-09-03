package com.sunmo.stockplatform.market.feature.api;

import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.common.error.ErrorCode;
import com.sunmo.stockplatform.market.feature.application.MarketFeatureEngine;
import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;
import com.sunmo.stockplatform.stock.application.StockService;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/stocks")
public class MarketFeatureController {
    private final StockService stocks;
    private final MarketFeatureEngine features;

    public MarketFeatureController(StockService stocks, MarketFeatureEngine features) {
        this.stocks = stocks;
        this.features = features;
    }

    @GetMapping("/{stockCode}/features/latest")
    public MarketFeatureSnapshot latest(@PathVariable @Pattern(regexp = "[A-Z0-9]{6,12}") String stockCode) {
        stocks.getByCode(stockCode);
        return features.latest(stockCode).orElseThrow(() -> new ApplicationException(
                ErrorCode.INVALID_REQUEST, HttpStatus.NOT_FOUND, "Feature snapshot not found: " + stockCode));
    }
}
