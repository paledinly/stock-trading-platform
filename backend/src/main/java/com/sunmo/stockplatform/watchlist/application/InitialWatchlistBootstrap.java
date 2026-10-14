/*
 * 서버가 켜질 때 "기본 모니터링" 관심종목 그룹을 준비하고, 미리 정한 대형주 20개를 채워 넣는 파일이에요.
 * 이미 있는 그룹과 항목은 그대로 두고, 빠진 종목만 뒤에 덧붙여요(여러 번 실행해도 결과가 같아요).
 * watchlist.initial.enabled가 false가 아니면 스프링이 시작 직후 자동으로 실행해요.
 */
package com.sunmo.stockplatform.watchlist.application;

import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import com.sunmo.stockplatform.watchlist.domain.WatchlistGroup;
import com.sunmo.stockplatform.watchlist.domain.WatchlistItem;
import com.sunmo.stockplatform.watchlist.infrastructure.WatchlistGroupRepository;
import com.sunmo.stockplatform.watchlist.infrastructure.WatchlistItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 초기 관심종목을 채우는 시작 실행기예요.
 * 새 학기 첫날 선생님이 기본 준비물 목록을 보고 사물함에 빠진 것만 채워 넣는 것처럼,
 * 기본 그룹이 없으면 만들고 목록 중 아직 없는 종목만 추가해요.
 */
@Component
@Order(5)
@ConditionalOnProperty(prefix = "watchlist.initial", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InitialWatchlistBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(InitialWatchlistBootstrap.class);
    private static final long OWNER_ID = 1L;
    private static final String GROUP_NAME = "기본 모니터링";
    // 기본으로 넣을 종목 코드 20개(삼성전자, SK하이닉스 등 시가총액 상위 종목으로 보여요).
    private static final List<String> STOCK_CODES = List.of(
            "005930", "000660", "005380", "000270", "035420",
            "035720", "068270", "207940", "373220", "005490",
            "012450", "034020", "105560", "055550", "015760",
            "196170", "247540", "086520", "277810", "028300");

    private final WatchlistGroupRepository groups;
    private final WatchlistItemRepository items;
    private final StockRepository stocks;

    /** 의존성 주입(필요한 부품을 스프링이 대신 만들어 넣어 주는 것)으로 그룹·항목·종목 레포지토리를 받아요. */
    public InitialWatchlistBootstrap(WatchlistGroupRepository groups, WatchlistItemRepository items,
            StockRepository stocks) {
        this.groups = groups;
        this.items = items;
        this.stocks = stocks;
    }

    /**
     * 기본 그룹을 찾거나 만들고, 빠진 기본 종목을 추가해요.
     * 받는 것: 시작 인자(쓰지 않음). 돌려주는 것: 없음(결과는 로그로 남겨요).
     * 트랜잭션(여러 작업을 '전부 성공하거나 전부 취소'하게 묶는 것) 안에서 실행돼요.
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 1) 같은 이름의 기본 그룹이 있으면 재사용하고, 없으면 맨 뒤 순서로 새로 만들어요.
        WatchlistGroup group = groups.findByOwnerIdOrderByDisplayOrderAscIdAsc(OWNER_ID).stream()
                .filter(candidate -> GROUP_NAME.equals(candidate.getName()))
                .findFirst()
                .orElseGet(() -> groups.save(new WatchlistGroup(OWNER_ID, GROUP_NAME,
                        groups.findByOwnerIdOrderByDisplayOrderAscIdAsc(OWNER_ID).size())));

        // 2) 종목 마스터에 있고 활성인 종목 중, 그룹에 아직 없는 것만 뒤에 붙여요.
        int displayOrder = Math.toIntExact(items.countByGroupId(group.getId()));
        int added = 0;
        for (String stockCode : STOCK_CODES) {
            var stock = stocks.findByStockCodeAndActiveTrue(stockCode).orElse(null);
            if (stock == null) {
                log.warn("Skipped initial watchlist stock because it is absent or inactive: {}", stockCode);
                continue;
            }
            if (items.existsByGroupIdAndStockId(group.getId(), stock.getId()))
                continue;
            items.save(new WatchlistItem(group, stock, displayOrder++));
            added++;
        }
        log.info("Initial watchlist is ready: group={}, added={}, configured={}", GROUP_NAME, added,
                STOCK_CODES.size());
    }
}
