/*
 * 종목별 최신 시세(마지막 틱)를 메모리와 Redis에 보관하는 저장소예요.
 * 메모리 사본은 서버 안에서 바로 읽고, Redis 사본은 TTL(자동 만료 시간)을 붙여 외부에서 볼 수 있게 남겨요.
 * MarketDataService가 틱마다 put을 부르고, 현재가가 필요한 서비스들이 get으로 읽어요.
 */
package com.sunmo.stockplatform.market.application;

import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import com.sunmo.stockplatform.market.domain.MarketTick;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 종목마다 "가장 최근 체결" 한 건만 기억하는 시세판이에요.
 * 증권사 객장 전광판처럼 새 체결이 오면 그 종목 칸의 숫자를 덮어써요.
 * Redis(메모리 기반 키-값 저장소)는 선택 사항이라 없거나 실패해도 메모리 사본은 계속 동작해요.
 */
@Component
public class QuoteStateStore {
    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Map<String, MarketTick> memory = new ConcurrentHashMap<>();

    /**
     * 하는 일: Redis 템플릿(있으면)과 시세 보관 기간(TTL)을 준비해요.
     * 받는 것: Redis 템플릿 제공자, 실시간 설정값(quoteTtl).
     */
    public QuoteStateStore(ObjectProvider<StringRedisTemplate> provider, RealtimeMarketProperties properties) {
        this.redis = provider.getIfAvailable();
        this.ttl = properties.quoteTtl();
    }

    /**
     * 하는 일: 종목의 최신 틱을 메모리에 덮어쓰고, Redis가 있으면 "quote:거래일:종목코드" 해시에도 써요.
     * 받는 것: 새 틱. 돌려주는 것: 없음.
     * Redis 쓰기 실패는 틱 처리를 멈추지 않도록 일부러 무시해요.
     */
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

    /**
     * 하는 일: 메모리에 있는 종목의 최신 틱을 꺼내요(Redis는 읽지 않아요).
     * 받는 것: 종목코드. 돌려주는 것: 최신 틱, 이번 실행 중 받은 적이 없으면 빈 Optional.
     */
    public Optional<MarketTick> get(String code) {
        return Optional.ofNullable(memory.get(code));
    }
}
