/*
 * 종목 하나의 현재가를 조회하는 REST API 입구예요.
 * GET /api/v1/stocks/{stockCode}/quote 요청을 받아 QuoteService에 넘기고 결과를 그대로 돌려줘요.
 * Web/Mobile 화면이 종목 현재가를 보여 줄 때 호출해요.
 */
package com.sunmo.stockplatform.quote.api;

import com.sunmo.stockplatform.quote.application.QuoteService;
import com.sunmo.stockplatform.quote.domain.StockQuote;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 현재가 조회 요청을 받는 컨트롤러예요.
 * 은행 창구 직원처럼 손님(화면)의 요청서를 받아 형식만 확인하고, 실제 조회는 안쪽 담당자(QuoteService)에게 맡겨요.
 */
@Validated
@RestController
@RequestMapping("/api/v1/stocks")
public class QuoteController {
    private final QuoteService quoteService;

    /**
     * 의존성 주입(필요한 부품을 스프링이 대신 만들어 넣어 주는 것)으로 QuoteService를 받아요.
     */
    public QuoteController(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    /**
     * 종목 코드로 현재가를 조회해요.
     * 받는 것: 대문자/숫자 6~12자리 종목 코드(형식이 틀리면 400 오류). 돌려주는 것: 현재가 스냅샷(StockQuote).
     */
    @GetMapping("/{stockCode}/quote")
    public StockQuote getQuote(@PathVariable @Pattern(regexp = "[A-Z0-9]{6,12}") String stockCode) {
        return quoteService.getQuote(stockCode);
    }
}
