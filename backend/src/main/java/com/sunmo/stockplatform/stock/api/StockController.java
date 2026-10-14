/*
 * 종목 검색 REST API(웹 주소로 데이터를 주고받는 약속된 창구)를 제공하는 파일이에요.
 * 사용자가 입력한 이름이나 코드 일부로 종목 목록을 찾아 돌려줘요.
 * Web/Mobile 화면의 종목 검색창이 이 API를 호출해요.
 */
package com.sunmo.stockplatform.stock.api;

import com.sunmo.stockplatform.stock.application.StockService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 종목 검색 요청을 받아 결과를 돌려주는 창구 클래스예요.
 * 도서관 안내 데스크에서 "이런 이름의 책 있어요?" 하고 물으면 목록을 찾아 건네주는 직원과 같아요.
 */
@Validated
@RestController
@RequestMapping("/api/v1/stocks")
public class StockController {
    private final StockService stockService;

    /**
     * 의존성 주입(필요한 부품을 Spring이 대신 만들어 넣어 주는 것)으로 검색 서비스를 받아요.
     * 받는 것: 종목 검색 서비스. 돌려주는 것: 없음(생성자).
     */
    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    /**
     * 검색어로 종목을 찾아 응답 형태로 바꿔 돌려줘요.
     * 받는 것: 검색어 q, 최대 개수 limit(1~50, 기본 20).
     * 돌려주는 것: 종목 응답 목록.
     */
    @GetMapping("/search")
    public List<StockResponse> search(@RequestParam("q") String query,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return stockService.search(query, limit).stream().map(StockResponse::from).toList();
    }
}
