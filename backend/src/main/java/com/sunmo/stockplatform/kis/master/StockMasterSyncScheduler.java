/*
 * 설정한 일정(cron)에 맞춰 종목 마스터 전체 동기화를 자동 실행하는 파일이에요.
 * kis.master.sync-enabled가 true일 때만 켜져요.
 * 스프링 스케줄러가 호출하고, 실제 작업은 StockMasterSyncService가 해요.
 */
package com.sunmo.stockplatform.kis.master;

import com.sunmo.stockplatform.stock.application.StockMasterSyncService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 종목 마스터 동기화를 정해진 시각에 깨우는 스케줄러(정해진 시간에 작업을 자동 실행하는 장치)예요.
 * 매일 아침 같은 시각에 울리는 알람처럼, 시간이 되면 동기화 서비스를 불러요.
 */
@Component
@ConditionalOnProperty(prefix = "kis.master", name = "sync-enabled", havingValue = "true")
public class StockMasterSyncScheduler {
    private final StockMasterSyncService service;

    /** 받는 것: 종목 마스터 동기화 서비스. 돌려주는 것: 없음(생성자). */
    public StockMasterSyncScheduler(StockMasterSyncService service) {
        this.service = service;
    }

    /**
     * kis.master.cron 일정(서울 시간)마다 KOSPI와 KOSDAQ 종목 마스터를 모두 동기화해요.
     * 받는 것: 없음. 돌려주는 것: 없음.
     */
    @Scheduled(cron = "${kis.master.cron}", zone = "Asia/Seoul")
    public void synchronize() {
        service.synchronizeAll();
    }
}
