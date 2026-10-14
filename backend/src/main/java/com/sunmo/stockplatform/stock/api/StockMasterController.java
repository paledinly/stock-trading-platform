/*
 * 종목 마스터(전체 종목 기본 정보 목록)를 KIS 파일에서 다시 받아 오도록 수동으로 요청하는 API 파일이에요.
 * 시작 시 자동 초기화가 실패했을 때 운영자가 다시 시도하는 용도로 쓰여요.
 * 운영자나 운영 스크립트가 POST /api/v1/stocks/master/sync 로 호출해요.
 */
package com.sunmo.stockplatform.stock.api;

import com.sunmo.stockplatform.stock.application.StockMasterSyncService;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

/**
 * 종목 마스터 동기화 요청을 받는 창구 클래스예요.
 * 가게 주인이 "거래처 목록 최신으로 다시 받아 와!" 하고 버튼을 누르는 것처럼, 요청 한 번에 전체 목록을 새로 맞춰요.
 */
@RestController
@RequestMapping("/api/v1/stocks/master")
public class StockMasterController {
    private final StockMasterSyncService service;

    /**
     * 동기화 서비스를 주입받아요.
     * 받는 것: 종목 마스터 동기화 서비스. 돌려주는 것: 없음(생성자).
     */
    public StockMasterController(StockMasterSyncService service) {
        this.service = service;
    }

    /**
     * KOSPI와 KOSDAQ 마스터 파일을 모두 받아 DB의 종목 정보를 최신으로 맞춰요.
     * 받는 것: 없음. 돌려주는 것: 처리한 종목 수와 처리 시각.
     */
    @PostMapping("/sync")
    public SyncResponse synchronize() {
        return new SyncResponse(service.synchronizeAll(), Instant.now());
    }

    /**
     * 동기화 결과를 담는 응답이에요.
     * 택배 영수증처럼 "몇 건을 언제 처리했는지"만 적어 돌려줘요.
     */
    public record SyncResponse(int synchronizedCount, Instant synchronizedAt) {
    }
}
