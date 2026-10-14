/*
 * 연구용 수정주가 일봉 전 종목 수집(sweep)을 수동으로 시작하고 진행 상태를 보는 API(프로그램끼리 요청·응답하는 창구)예요.
 * 실제 수집은 AdjustedDailySweepService가 백그라운드에서 해요.
 * 운영자가 /api/v1/market-data/adjusted-daily로 직접 호출해요.
 */
package com.sunmo.stockplatform.candle.api;

import com.sunmo.stockplatform.candle.application.AdjustedDailySweepService;
import com.sunmo.stockplatform.candle.application.AdjustedDailySweepService.SweepStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 수정주가 일봉 수집의 시작 버튼과 진행 표시판 역할을 하는 REST(HTTP 주소로 기능을 제공하는 방식) 컨트롤러예요.
 * 세탁기 시작 버튼처럼 누르면 바로 돌아가기 시작했는지만 알려 주고, 끝날 때까지 기다리지 않아요.
 */
@RestController
@RequestMapping("/api/v1/market-data/adjusted-daily")
public class AdjustedDailySweepController {
    private final AdjustedDailySweepService service;

    /** 하는 일: 수집 서비스를 주입받아요(의존성 주입: 스프링이 필요한 객체를 넣어 주는 것). */
    public AdjustedDailySweepController(AdjustedDailySweepService service) { this.service = service; }

    /**
     * 202 when a background sweep started; 409 when one is running, KIS is disabled or the session guard applies.
     * 하는 일: 백그라운드 수집을 시작해요.
     * 돌려주는 것: 시작했으면 202, 이미 실행 중이거나 KIS 꺼짐·장중 보호 시간이면 409와 현재 상태.
     */
    @PostMapping("/sweep")
    public ResponseEntity<SweepStatus> sweep() {
        boolean started = service.start();
        return ResponseEntity.status(started ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT).body(service.status());
    }

    /** 하는 일: 현재(또는 마지막) 수집 진행 상태를 돌려줘요. */
    @GetMapping("/status")
    public SweepStatus status() { return service.status(); }
}
