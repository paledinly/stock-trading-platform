package com.sunmo.stockplatform.market.application;

import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import com.sunmo.stockplatform.market.domain.MarketTick;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class QuoteStateStore {
    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Map<String, MarketTick> memory = new ConcurrentHashMap<>();

    public QuoteStateStore(ObjectProvider<StringRedisTemplate> provider, RealtimeMarketProperties properties) {
        this.redis = provider.getIfAvailable();
        this.ttl = properties.quoteTtl();
    }

    public void put(MarketTick tick) {
        memory.put(tick.stockCode(), tick);
        if (redis == null)
            return;
        String key = "quote:" + tick.businessDate() + ":" + tick.stockCode();
        try {
            redis.opsForHash().putAll(key,
                    Map.of("price", tick.price().toPlainString(), "volume", Long.toString(tick.cumulativeVolume()),
                            "tradingValue",
                            tick.cumulativeTradingValue() == null ? "" : tick.cumulativeTradingValue().toPlainString(),
                            "occurredAt", tick.occurredAt().toString(), "sequence", Long.toString(tick.sequence())));
            redis.expire(key, ttl);
        } catch (RuntimeException ignored) {
        }
    }

    public Optional<MarketTick> get(String code) {
        return Optional.ofNullable(memory.get(code));
    }
}
