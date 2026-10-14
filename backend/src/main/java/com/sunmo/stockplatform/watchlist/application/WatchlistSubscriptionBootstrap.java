/*
 * 서버가 켜질 때, DB에 실시간 고정으로 저장된 관심종목들을 실시간 구독 목록에 다시 올리는 파일이에요.
 * 서버를 재시작해도 사용자가 고정해 둔 종목의 실시간 시세가 끊기지 않게 해 줘요.
 * 스프링이 애플리케이션 시작 직후 자동으로 실행해요(InitialWatchlistBootstrap 다음 순서).
 */
package com.sunmo.stockplatform.watchlist.application;

import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.watchlist.infrastructure.WatchlistItemRepository;
import org.slf4j.*;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 시작 시 실시간 고정 종목을 복원하는 실행기예요.
 * 가게 문을 열 때 단골손님 예약 장부를 펼쳐 자리를 다시 잡아 두는 직원처럼,
 * 저장된 고정 종목을 구독 레지스트리(WATCHLIST 출처)에 다시 등록해요.
 */
@Component
@Order(10)
public class WatchlistSubscriptionBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(WatchlistSubscriptionBootstrap.class);
    private final WatchlistItemRepository items;
    private final RealtimeSubscriptionRegistry subscriptions;

    /** 의존성 주입(필요한 부품을 스프링이 대신 만들어 넣어 주는 것)으로 항목 저장소와 구독 레지스트리를 받아요. */
    public WatchlistSubscriptionBootstrap(WatchlistItemRepository items, RealtimeSubscriptionRegistry subscriptions) {
        this.items = items;
        this.subscriptions = subscriptions;
    }

    /**
     * 기본 사용자(owner 1)의 실시간 고정 종목을 하나씩 구독에 추가해요.
     * 받는 것: 시작 인자(쓰지 않음). 돌려주는 것: 없음.
     * 슬롯이 꽉 차서 실패하면 뒤 종목도 어차피 실패할 테니 경고만 남기고 멈추는 것으로 보여요.
     */
    @Override
    public void run(ApplicationArguments args) {
        for (String code : items.findDistinctRealtimePinnedStockCodesByOwnerId(1L)) {
            try {
                subscriptions.add(code, RealtimeSubscriptionRegistry.Source.WATCHLIST);
            } catch (IllegalStateException error) {
                log.warn("Skipped watchlist realtime subscription for {}: {}", code, error.getMessage());
                break;
            }
        }
    }
}
