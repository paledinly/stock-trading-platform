/*
 * 연구용 상장폐지 종목 과거 기록 수집 API예요. 목록을 넣고, 수집을 시작하고, 진행 상태를 봐요.
 */
package com.sunmo.stockplatform.candle.api;

import com.sunmo.stockplatform.candle.application.DelistedHistoryService;
import com.sunmo.stockplatform.candle.application.DelistedHistoryService.Entry;
import com.sunmo.stockplatform.candle.application.DelistedHistoryService.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 상장폐지 종목 과거 기록 수집 API예요. */
@RestController
@RequestMapping("/api/v1/market-data/delisted-history")
public class DelistedHistoryController {
    private final DelistedHistoryService service;

    /** 하는 일: 수집 서비스를 주입받아요. */
    public DelistedHistoryController(DelistedHistoryService service) { this.service = service; }

    /** 하는 일: POST /stocks. 상장폐지 목록을 넣어요. 돌려주는 것: 받은 줄 수와 새로 넣은 종목 수. */
    @PostMapping("/stocks")
    public Map<String, Integer> register(@RequestBody List<Entry> entries) {
        return Map.of("received", entries.size(), "added", service.register(entries));
    }

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
