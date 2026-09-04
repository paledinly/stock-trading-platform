package com.sunmo.stockplatform.backtest.api;

import com.sunmo.stockplatform.backtest.application.BacktestService;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/backtests")
public class BacktestController {
    private final BacktestService service;
    private final StockCandleRepository candles;

    public BacktestController(BacktestService service, StockCandleRepository candles) {
        this.service = service;
        this.candles = candles;
    }

    @GetMapping("/run")
    public BacktestDtos.BacktestResponse run(@RequestParam @Pattern(regexp = "[A-Z0-9]{6,12}") String stockCode,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) Long settingId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return service.run(stockCode, from, to, settingId, limit);
    }

    @GetMapping("/stocks")
    public List<BacktestDtos.BacktestableStock> stocks() {
        return candles.findBacktestableStocks("5M").stream()
                .map(row -> new BacktestDtos.BacktestableStock(
                        row.getStockCode(),
                        row.getStockName(),
                        row.getMarket().name(),
                        row.getCandleCount(),
                        row.getFirstCandleAt(),
                        row.getLastCandleAt()))
                .toList();
    }
}
