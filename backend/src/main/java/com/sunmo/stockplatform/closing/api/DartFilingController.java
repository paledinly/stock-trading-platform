/*
 * 공시(DART) 과거 수집을 수동으로 시작하고 진행 상황을 보는 API예요. 실제 수집은 DartFilingService가 백그라운드에서 해요.
 * 연구용이라 추천 점수나 선정 로직은 이 데이터를 읽지 않아요.
 */
package com.sunmo.stockplatform.closing.api;

import com.sunmo.stockplatform.closing.application.DartFilingService;
import com.sunmo.stockplatform.closing.application.DartFilingService.BackfillStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 공시 과거 수집 시작·상태 조회 API예요. */
@RestController
@RequestMapping("/api/v1/market-data/dart-filings")
public class DartFilingController {
    private final DartFilingService service;

    /** 하는 일: 공시 수집 서비스를 주입받아요. */
    public DartFilingController(DartFilingService service) { this.service = service; }

    /**
     * 하는 일: POST /backfill?from=&to=. from~to(포함, to 기본 오늘) 공시 목록을 최신부터 거꾸로 받기 시작해요.
     * 돌려주는 것: 시작했으면 202, 인증키 없음·기간 오류·이미 실행 중이면 409와 현재 상태.
     */
    @PostMapping("/backfill")
    public ResponseEntity<BackfillStatus> backfill(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        boolean started = service.startBackfill(from, to == null ? LocalDate.now(java.time.ZoneId.of("Asia/Seoul")) : to);
        return ResponseEntity.status(started ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT).body(service.backfillStatus());
    }

    /** 하는 일: GET /backfill/status. 과거 수집의 현재(또는 마지막) 상태를 돌려줘요. */
    @GetMapping("/backfill/status")
    public BackfillStatus status() { return service.backfillStatus(); }
}
