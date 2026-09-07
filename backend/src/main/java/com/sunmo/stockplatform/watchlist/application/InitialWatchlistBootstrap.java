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

@Component
@Order(5)
@ConditionalOnProperty(prefix = "watchlist.initial", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InitialWatchlistBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(InitialWatchlistBootstrap.class);
    private static final long OWNER_ID = 1L;
    private static final String GROUP_NAME = "기본 모니터링";
    private static final List<String> STOCK_CODES = List.of(
            "005930", "000660", "005380", "000270", "035420",
            "035720", "068270", "207940", "373220", "005490",
            "012450", "034020", "105560", "055550", "015760",
            "196170", "247540", "086520", "277810", "028300");

    private final WatchlistGroupRepository groups;
    private final WatchlistItemRepository items;
    private final StockRepository stocks;

    public InitialWatchlistBootstrap(WatchlistGroupRepository groups, WatchlistItemRepository items,
            StockRepository stocks) {
        this.groups = groups;
        this.items = items;
        this.stocks = stocks;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        WatchlistGroup group = groups.findByOwnerIdOrderByDisplayOrderAscIdAsc(OWNER_ID).stream()
                .filter(candidate -> GROUP_NAME.equals(candidate.getName()))
                .findFirst()
                .orElseGet(() -> groups.save(new WatchlistGroup(OWNER_ID, GROUP_NAME,
                        groups.findByOwnerIdOrderByDisplayOrderAscIdAsc(OWNER_ID).size())));

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
