package com.sunmo.stockplatform.watchlist.application;

import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.watchlist.infrastructure.WatchlistItemRepository;
import org.slf4j.*;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class WatchlistSubscriptionBootstrap implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(WatchlistSubscriptionBootstrap.class);
    private final WatchlistItemRepository items;private final RealtimeSubscriptionRegistry subscriptions;
    public WatchlistSubscriptionBootstrap(WatchlistItemRepository items,RealtimeSubscriptionRegistry subscriptions){this.items=items;this.subscriptions=subscriptions;}
    @Override public void run(ApplicationArguments args){for(String code:items.findDistinctStockCodesByOwnerId(1L)){try{subscriptions.add(code,RealtimeSubscriptionRegistry.Source.WATCHLIST);}catch(IllegalStateException error){log.warn("Skipped watchlist realtime subscription for {}: {}",code,error.getMessage());break;}}}
}
