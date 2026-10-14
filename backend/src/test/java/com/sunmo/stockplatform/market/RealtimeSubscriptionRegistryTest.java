/*
 * RealtimeSubscriptionRegistry(실시간 구독 등록부)가 여러 출처의 구독을 한 슬롯으로 합치고
 * 최대 구독 수를 지키는지 확인해요. KIS 실시간 구독 수에는 한도가 있어서 중요해요.
 */
package com.sunmo.stockplatform.market;

import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/** 실시간 구독 슬롯 공유와 한도 적용을 점검하는 테스트예요. */
class RealtimeSubscriptionRegistryTest {
    /**
     * 같은 종목을 여러 출처가 구독하면 슬롯을 하나만 쓰고, 한도를 넘으면 막는지 확인해요.
     * 준비: 한도 1로 설정하고 005930을 관심종목·시세 두 출처에서 추가해요.
     * 기대: 목록엔 005930 하나뿐이고, 다른 종목 추가는 예외가 나며, 한 출처만 해제해도 구독은 유지돼요.
     */
    @Test void sharesOneSlotAcrossSourcesAndEnforcesLimit(){
        var properties=new RealtimeMarketProperties(true,URI.create("ws://localhost"),Duration.ZERO,Duration.ofHours(1),10,1,Duration.ofMinutes(1));
        var registry=new RealtimeSubscriptionRegistry(properties);
        registry.add("005930",RealtimeSubscriptionRegistry.Source.WATCHLIST);
        registry.add("005930",RealtimeSubscriptionRegistry.Source.QUOTE);
        assertThat(registry.all()).containsExactly("005930");
        assertThatThrownBy(()->registry.add("000660",RealtimeSubscriptionRegistry.Source.WATCHLIST)).isInstanceOf(IllegalStateException.class);
        registry.remove("005930",RealtimeSubscriptionRegistry.Source.WATCHLIST);
        assertThat(registry.all()).contains("005930");
    }
}
