/*
 * KIS 종목별 투자자(외국인·기관) 일별 기록이 얼마나 과거까지 조회되는지 확인하는 진단 API예요(연구용).
 * 기관·외국인 연속 순매수 종목의 이후 성과를 검증할 수 있는지 판단하려고 만들었어요. 운영 수집이나 점수에는 쓰지 않아요.
 */
package com.sunmo.stockplatform.candle.api;

import com.sunmo.stockplatform.kis.investor.KisInvestorHistoryProbeClient;
import com.sunmo.stockplatform.kis.investor.KisInvestorHistoryProbeClient.Kind;
import com.sunmo.stockplatform.kis.investor.KisInvestorHistoryProbeClient.Probe;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 투자자 일별 기록 조회 진단 API예요. */
@RestController
@RequestMapping("/api/v1/market-data/investor-history")
public class InvestorHistoryProbeController {
    private final KisInvestorHistoryProbeClient client;

    /** 하는 일: 투자자 기록 진단 클라이언트를 주입받아요. */
    public InvestorHistoryProbeController(KisInvestorHistoryProbeClient client) { this.client = client; }

    /**
     * 하는 일: GET /probe?code=005930&date=2024-01-15&kind=DAILY. KIS에 한 번 조회해 응답 모양(행 수, 날짜 범위,
     * 필드 이름, 첫·마지막 행)을 돌려줘요. kind는 DAILY(기준일 지정) 또는 RECENT(최근 기록)예요. 호출 1회예요.
     */
    @GetMapping("/probe")
    public Probe probe(@RequestParam String code,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "DAILY") Kind kind) {
        return client.probe(kind, code, date == null ? LocalDate.now(java.time.ZoneId.of("Asia/Seoul")) : date);
    }
}
