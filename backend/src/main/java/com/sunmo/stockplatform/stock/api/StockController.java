package com.sunmo.stockplatform.stock.api;

import com.sunmo.stockplatform.stock.application.StockService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/stocks")
public class StockController {
    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @GetMapping("/search")
    public List<StockResponse> search(@RequestParam("q") String query,
                                      @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return stockService.search(query, limit).stream().map(StockResponse::from).toList();
    }

    @GetMapping("/{stockCode}")
    public StockResponse get(@PathVariable @Pattern(regexp = "[A-Z0-9]{6,12}") String stockCode) {
        return StockResponse.from(stockService.getByCode(stockCode));
    }
}

