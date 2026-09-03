package com.sunmo.stockplatform.stock.application;

import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "kis.master", name = "sync-on-startup", havingValue = "true")
public class StockMasterBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StockMasterBootstrap.class);
    private final StockRepository repository;
    private final StockMasterSyncService service;

    public StockMasterBootstrap(StockRepository repository, StockMasterSyncService service) {
        this.repository = repository;
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repository.count() > 0)
            return;
        try {
            int count = service.synchronizeAll();
            log.info("Initialized {} stocks from KIS master files", count);
        } catch (RuntimeException exception) {
            log.warn("Stock master initialization failed; POST /api/v1/stocks/master/sync to retry", exception);
        }
    }
}
