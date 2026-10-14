/*
 * 저장된 5분봉으로 Scanner 설정을 과거 구간에 다시 돌려 보는 백테스트 API(프로그램끼리 요청·응답하는 창구)예요.
 * 백테스트 실행과, 백테스트할 수 있는(확정 5분봉이 있는) 종목 목록 조회를 제공해요.
 * 웹 화면의 백테스트 페이지가 /api/v1/backtests로 호출해요.
 */
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

/**
 * 백테스트(과거 데이터로 규칙을 시험해 보는 것) 요청 창구인 REST(HTTP 주소로 기능을 제공하는 방식) 컨트롤러예요.
 * 녹화된 경기 영상을 다시 틀어 놓고 "이 작전을 썼다면 어땠을까"를 확인해 보는 분석실 접수대와 같아요.
 * 결과는 가상 탐지와 가상 성과일 뿐이며, 실제 주문이나 수익성 검증이 아니에요.
 */
@Validated
@RestController
@RequestMapping("/api/v1/backtests")
public class BacktestController {
    private final BacktestService service;
    private final StockCandleRepository candles;

    /** 하는 일: 백테스트 서비스와 봉 레포지토리(DB 창구)를 주입받아요(의존성 주입). */
    public BacktestController(BacktestService service, StockCandleRepository candles) {
        this.service = service;
        this.candles = candles;
    }

    /**
     * 하는 일: 한 종목의 [from, to) 구간을 Scanner 설정으로 백테스트해요.
     * 받는 것: 종목코드, 시작·끝 시각, 특정 설정 ID(없으면 활성 설정 전체), 돌려받을 가상 탐지 최대 개수(1~200, 기본 50).
     * 돌려주는 것: 평가한 봉 수, 설정별 요약, 최근 가상 탐지 목록.
     */
    @GetMapping("/run")
    public BacktestDtos.BacktestResponse run(@RequestParam @Pattern(regexp = "[A-Z0-9]{6,12}") String stockCode,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) Long settingId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return service.run(stockCode, from, to, settingId, limit);
    }

    /**
     * 하는 일: 확정 5분봉이 저장된 활성 종목 목록을 봉 개수·기간과 함께 돌려줘요.
     * 받는 것: 없음. 돌려주는 것: 최근 봉이 있는 종목부터 정렬된 목록.
     */
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
