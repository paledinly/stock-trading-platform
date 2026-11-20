/*
 * 종목 하나의 하루 가격 흐름(1분봉)을 화면에 내주는 API예요.
 * AI 장전의 기록·채점, 후보 평가 표에서 종목을 누르면 /api/v1/stocks/{종목코드}/intraday-flow?date=날짜로 불러요.
 * 실시간 구독과 상관없이 KIS 분봉 조회로 받고, 저장하지 않는 표시 전용이에요.
 */
package com.sunmo.stockplatform.candle.api;

import com.sunmo.stockplatform.candle.application.IntradayFlowService;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 하루 1분봉 흐름 조회 창구예요. */
@Validated
@RestController
@RequestMapping("/api/v1/stocks")
public class IntradayFlowController {
    private final IntradayFlowService service;

    public IntradayFlowController(IntradayFlowService service) { this.service = service; }

    /** 하는 일: 종목의 그날 1분봉 흐름을 돌려줘요. 받는 것: 6자리 종목코드, 날짜(yyyy-MM-dd). */
    @GetMapping("/{stockCode}/intraday-flow")
    public IntradayFlowService.Flow flow(@PathVariable @Pattern(regexp = "[0-9A-Z]{6}") String stockCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.flow(stockCode, date);
    }
}
