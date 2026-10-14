/*
 * 종목의 봉(일정 시간 동안의 시가·고가·저가·종가·거래량 묶음) 목록을 조회하는 API예요.
 * 기간과 봉 단위(기본 5분봉)를 받아 CandleQueryService에서 읽은 결과를 응답 모양으로 바꿔요.
 * 웹 화면의 종목 차트가 /api/v1/stocks/{종목코드}/candles로 호출해요.
 */
package com.sunmo.stockplatform.candle.api;

import com.sunmo.stockplatform.candle.application.CandleQueryService;
import com.sunmo.stockplatform.stock.application.StockService;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 차트용 봉 데이터를 내주는 REST(HTTP 주소로 기능을 제공하는 방식) 컨트롤러예요.
 * 도서관 대출 창구처럼 요청한 기간의 기록(봉)을 꺼내 정해진 양식으로 건네줘요.
 */
@Validated
@RestController
@RequestMapping("/api/v1/stocks")
public class CandleController {
    private final CandleQueryService candles;

    /** 하는 일: 봉 조회 서비스를 주입받아요(의존성 주입). */
    public CandleController(CandleQueryService candles) {
        this.candles = candles;
    }

    /**
     * 하는 일: 종목의 봉을 기간으로 조회해요. 오늘 5분봉을 조회하면 빠진 봉을 KIS 분봉으로 보충할 수도 있어요.
     * 받는 것: 종목코드, 봉 단위(timeFrame, 기본 "5M"), 시작·끝 시각(from, to).
     * 돌려주는 것: 시작시각 순 봉 목록. source는 REALTIME(실시간 집계) 또는 BACKFILL(나중에 보충)이에요.
     */
    @GetMapping("/{stockCode}/candles")
    public List<Response> get(@PathVariable @Pattern(regexp = "[A-Z0-9]{6,12}") String stockCode,
            @RequestParam(defaultValue = "5M") String timeFrame, @RequestParam Instant from, @RequestParam Instant to) {
        return candles.get(stockCode, timeFrame, from, to).stream()
                .map(c -> new Response(c.getStartTime(), c.getOpen(), c.getHigh(), c.getLow(), c.getClose(),
                        c.getVolume(), c.getTradingValue(), c.isFinalCandle(), c.getRevision(), c.getSource().name()))
                .toList();
    }

    /** 봉 하나의 응답 모양이에요. finalCandle은 확정봉 여부예요. */
    public record Response(Instant startTime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
            long volume, BigDecimal tradingValue, boolean finalCandle, int revision, String source) {
    }
}
