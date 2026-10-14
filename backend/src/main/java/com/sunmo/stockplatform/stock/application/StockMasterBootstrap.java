/*
 * 서버가 처음 켜질 때 종목 테이블이 비어 있으면 KIS 마스터 파일로 종목 목록을 채우는 파일이에요.
 * kis.master.sync-on-startup=true 설정일 때만 동작해요.
 * Spring Boot가 애플리케이션 시작 직후 자동으로 실행해요.
 */
package com.sunmo.stockplatform.stock.application;

import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 서버 시작 때 종목 목록이 비어 있으면 한 번 채워 넣는 클래스예요.
 * 가게 문을 열 때 진열대가 텅 비어 있으면 창고에서 상품을 한 번에 채워 두는 개점 담당자와 같아요.
 * Order(0)이라 다른 시작 작업보다 먼저 실행돼요.
 */
@Component
@Order(0)
@ConditionalOnProperty(prefix = "kis.master", name = "sync-on-startup", havingValue = "true")
public class StockMasterBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StockMasterBootstrap.class);
    private final StockRepository repository;
    private final StockMasterSyncService service;

    /**
     * 종목 레포지토리(DB에서 데이터를 꺼내고 저장하는 창구)와 동기화 서비스를 주입받아요.
     * 받는 것: 종목 레포지토리, 마스터 동기화 서비스. 돌려주는 것: 없음(생성자).
     */
    public StockMasterBootstrap(StockRepository repository, StockMasterSyncService service) {
        this.repository = repository;
        this.service = service;
    }

    /**
     * 종목이 하나도 없을 때만 마스터 동기화를 실행해요.
     * 받는 것: 시작 인자(사용하지 않아요). 돌려주는 것: 없음.
     * 실패해도 서버는 계속 뜨고, 경고 로그로 수동 재시도 방법을 알려 줘요.
     */
    @Override
    public void run(ApplicationArguments args) {
        // 이미 종목이 있으면 다시 받지 않아요(매 재시작마다 KIS 파일을 받지 않도록).
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
