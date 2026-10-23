/*
 * 지수(KOSPI·KOSDAQ) API예요. GET /api/v1/market-data/indices는 화면 고정 표시용 최신 지수,
 * POST /api/v1/market-data/index-daily/backfill은 과거 지수 일봉 수집(외부 요인 연구용 기록)이에요.
 * POST /api/v1/market-data/overseas-daily/backfill은 미국 지수·원/달러·EWY 과거 일봉 수집(외부 요인 연구용 기록)이에요.
 */
package com.sunmo.stockplatform.marketindex.api;

import com.sunmo.stockplatform.marketindex.application.MarketIndexService;
import com.sunmo.stockplatform.marketindex.application.OverseasDailyService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 지수 조회·수집 요청을 MarketIndexService로 넘겨요. */
@RestController
@RequestMapping("/api/v1/market-data")
public class MarketIndexController {
    private final MarketIndexService indices;
    private final OverseasDailyService overseas;

    public MarketIndexController(MarketIndexService indices, OverseasDailyService overseas) {
        this.indices = indices;
        this.overseas = overseas;
    }

    /** 하는 일: 지수별 최신 값(장중 관측 또는 마지막 일봉 종가)과 전일 종가를 돌려줘요. */
    @GetMapping("/indices")
    public List<MarketIndexService.IndexView> latest() {
        return indices.latest();
    }

    /**
     * 하는 일: from~to(기본 to=오늘) 지수 일봉을 KIS에서 받아 저장해요. 지수마다 50행씩 거꾸로 나눠 받아요.
     * 받는 것: from, to(ISO 날짜). 돌려주는 것: 지수별 요청 수·저장 수·받은 기간.
     */
    @PostMapping("/index-daily/backfill")
    public List<MarketIndexService.BackfillResult> backfill(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return indices.backfill(from, to == null ? LocalDate.now(java.time.ZoneId.of("Asia/Seoul")) : to);
    }

    /**
     * 하는 일: from~to(기본 to=오늘) 해외 일봉(미국 지수·원/달러·EWY)을 KIS에서 받아 저장해요. 종목마다 거꾸로 나눠 받아요.
     * 받는 것: from, to(ISO 날짜). 돌려주는 것: 종목별 요청 수·저장 수·받은 기간·오류.
     */
    @PostMapping("/overseas-daily/backfill")
    public List<OverseasDailyService.BackfillResult> overseasBackfill(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return overseas.backfill(from, to == null ? LocalDate.now(java.time.ZoneId.of("Asia/Seoul")) : to);
    }
}
