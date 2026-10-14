/*
 * 전체 시장 스캔 관련 HTTP API(프로그램끼리 요청·응답을 주고받는 창구)를 /api/v1/market-wide 아래에 열어요.
 * 수동 스캔 실행, 스캔·보강 대기열 상태 조회, 거래일 커버리지 조회를 제공해요.
 * 웹 화면(web)과 운영자가 호출해요.
 */
package com.sunmo.stockplatform.marketwide.api;

import com.sunmo.stockplatform.marketwide.application.MarketWideScannerService;
import com.sunmo.stockplatform.marketwide.application.MarketWideScanCoordinator;
import com.sunmo.stockplatform.marketwide.application.MarketWideDiagnostics;
import com.sunmo.stockplatform.marketwide.application.MarketCoverageService;
import com.sunmo.stockplatform.stock.domain.Market;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDate;

/**
 * 전체 시장 스캔 기능을 바깥에 열어 주는 접수 창구예요.
 * 은행 창구 직원처럼 요청을 받아 입력값을 확인하고, 실제 일은 뒤쪽 담당자(서비스)에게 넘겨요.
 */
@Validated
@RestController
@RequestMapping("/api/v1/market-wide")
public class MarketWideScannerController {
    private final MarketWideScanCoordinator scans;
    private final MarketWideDiagnostics diagnostics;
    private final MarketCoverageService coverage;
    private final com.sunmo.stockplatform.marketwide.application.BroadEnrichmentQueue enrichment;

    /**
     * 하는 일: 필요한 담당자들(스캔 조율, 진단, 커버리지, 보강 대기열)을 받아 보관해요.
     * 받는 것: 위 네 가지 서비스. 돌려주는 것: 없음(생성자예요).
     */
    public MarketWideScannerController(MarketWideScanCoordinator scans, MarketWideDiagnostics diagnostics,
            MarketCoverageService coverage, com.sunmo.stockplatform.marketwide.application.BroadEnrichmentQueue enrichment) {
        this.scans = scans;
        this.diagnostics = diagnostics;
        this.coverage = coverage;
        this.enrichment = enrichment;
    }

    /**
     * 하는 일: 전체 시장 스캔을 지금 수동으로 한 번 실행해요.
     * 받는 것: 시장(없으면 전체), 훑어볼 종목 수 limit(1~120, 기본 40),
     *          정밀 후보 수 candidates(1~41, 기본 12, 41은 실시간 구독 한도), ETF 포함 여부.
     * 돌려주는 것: 후보 목록, 순위 출처, 정밀 구독 배정, 시장 분위기 등을 담은 스캔 결과.
     */
    @GetMapping("/scan")
    public MarketWideDtos.BroadScanResponse scan(@RequestParam(required = false) Market market,
            @RequestParam(defaultValue = "40") @Min(1) @Max(120) int limit,
            @RequestParam(defaultValue = "12") @Min(1) @Max(41) int candidates,
            @RequestParam(defaultValue = "false") boolean includeEtf) {
        return scans.manual(market, limit, candidates, includeEtf);
    }

    /**
     * 하는 일: 전체 시장 스캔의 현재 진단 상태와 보강 대기열 상태를 함께 보여 줘요.
     * 받는 것: 없음. 돌려주는 것: 진단 스냅샷(그 순간의 상태 요약).
     */
    @GetMapping("/status")
    public MarketWideDiagnostics.Snapshot status() {
        return diagnostics.snapshot(enrichment.snapshot());
    }

    /**
     * 하는 일: 거래일 하루 동안 시장을 얼마나 넓게 살펴봤는지(커버리지) 집계해 보여 줘요.
     * 받는 것: 거래일(없으면 서비스가 기본 날짜를 정해요). 돌려주는 것: 커버리지 집계 결과.
     */
    @GetMapping("/coverage")
    public MarketWideDtos.CoverageResponse coverage(@RequestParam(required = false) LocalDate date) {
        return coverage.coverage(date);
    }
}
