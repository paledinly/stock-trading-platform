/*
 * 연구용 투자자(외국인·기관·개인) 일별 순매수 수집을 시작하고 진행 상태를 보는 API예요.
 */
package com.sunmo.stockplatform.candle.api;

import com.sunmo.stockplatform.candle.application.InvestorFlowHistoryService;
import com.sunmo.stockplatform.candle.application.InvestorFlowHistoryService.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 투자자 일별 순매수 수집 API예요. */
@RestController
@RequestMapping("/api/v1/market-data/investor-flow")
public class InvestorFlowHistoryController {
    private final InvestorFlowHistoryService service;

    /** 하는 일: 수집 서비스를 주입받아요. */
    public InvestorFlowHistoryController(InvestorFlowHistoryService service) { this.service = service; }

    /** 하는 일: POST /backfill. 수집을 지금 시작해요(장중 보호 시간이거나 이미 실행 중이면 409). */
    @PostMapping("/backfill")
    public ResponseEntity<Status> backfill() {
        boolean started = service.start();
        return ResponseEntity.status(started ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT).body(service.status());
    }

    /** 하는 일: GET /status. 현재(또는 마지막) 수집 상태를 돌려줘요. */
    @GetMapping("/status")
    public Status status() { return service.status(); }
}
