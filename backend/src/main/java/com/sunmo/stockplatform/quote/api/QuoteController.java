package com.sunmo.stockplatform.quote.api;

import com.sunmo.stockplatform.quote.application.QuoteService;
import com.sunmo.stockplatform.quote.domain.StockQuote;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/stocks")
public class QuoteController {
    private final QuoteService quoteService;

    public QuoteController(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    @GetMapping("/{stockCode}/quote")
    public StockQuote getQuote(@PathVariable @Pattern(regexp = "[A-Z0-9]{6,12}") String stockCode) {
        return quoteService.getQuote(stockCode);
    }
}
