package com.sunmo.stockplatform.market;

import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class RealtimeSubscriptionRegistryTest {
    @Test void sharesOneSlotAcrossSourcesAndEnforcesLimit(){
        var properties=new RealtimeMarketProperties(true,URI.create("ws://localhost"),Duration.ZERO,Duration.ofHours(1),10,1);
        var registry=new RealtimeSubscriptionRegistry(properties);
        registry.add("005930",RealtimeSubscriptionRegistry.Source.WATCHLIST);
        registry.add("005930",RealtimeSubscriptionRegistry.Source.QUOTE);
        assertThat(registry.all()).containsExactly("005930");
        assertThatThrownBy(()->registry.add("000660",RealtimeSubscriptionRegistry.Source.WATCHLIST)).isInstanceOf(IllegalStateException.class);
        registry.remove("005930",RealtimeSubscriptionRegistry.Source.WATCHLIST);
        assertThat(registry.all()).contains("005930");
    }
}
