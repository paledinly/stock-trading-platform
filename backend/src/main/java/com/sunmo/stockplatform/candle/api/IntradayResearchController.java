/*
 * 연구용 장중 데이터(분봉) 수집을 수동으로 시작하고 진행 상황을 보는 API예요. 실제 수집은 IntradayResearchService가 해요.
 */
package com.sunmo.stockplatform.candle.api;

import com.sunmo.stockplatform.candle.application.IntradayResearchService;
import com.sunmo.stockplatform.candle.application.IntradayResearchService.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 장중 연구 데이터 수집 시작·상태 API예요. */
@RestController
@RequestMapping("/api/v1/market-data/intraday-research")
public class IntradayResearchController {
    private final IntradayResearchService service;

    /** 하는 일: 수집 서비스를 주입받아요. */
    public IntradayResearchController(IntradayResearchService service) { this.service = service; }

    /** 하는 일: POST /backfill. 시작했으면 202, 이미 실행 중·KIS 꺼짐·장중 보호 시간이면 409와 현재 상태. */
    @PostMapping("/backfill")
    public ResponseEntity<Status> backfill() {
        boolean started = service.start();
        return ResponseEntity.status(started ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT).body(service.status());
    }

    /** 하는 일: GET /status. 현재(또는 마지막) 수집 상태예요. */
    @GetMapping("/status")
    public Status status() { return service.status(); }
}
