package com.sunmo.stockplatform.kis.master;

import com.sunmo.stockplatform.stock.application.StockMasterSyncService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "kis.master", name = "sync-enabled", havingValue = "true")
public class StockMasterSyncScheduler {
    private final StockMasterSyncService service;

    public StockMasterSyncScheduler(StockMasterSyncService service) {
        this.service = service;
    }

    @Scheduled(cron = "${kis.master.cron}", zone = "Asia/Seoul")
    public void synchronize() {
        service.synchronizeAll();
    }
}

