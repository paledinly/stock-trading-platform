/*
 * 실시간 시세 수집의 기본 설정값(market.realtime.*)을 담아요.
 * WebSocket 주소, 봉 확정 대기 시간(watermark), 시세 보관 시간, SSE 재전송 보관 개수, 구독 한도, 연결 멈춤 판단 시간을 가져요.
 * KIS 실시간 클라이언트, MarketDataService, QuoteStateStore, MarketEventGateway, RealtimeSubscriptionRegistry가 읽어요.
 */
package com.sunmo.stockplatform.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;

/**
 * 실시간 수집 설정 묶음이에요.
 * candleWatermark: 5분봉이 끝난 뒤 늦은 틱을 얼마나 기다렸다 확정할지(기본 2초는 application.yml에서 정해요),
 * quoteTtl: Redis 시세 자동 만료 시간, replaySize: SSE 재연결 시 다시 보내 줄 최근 이벤트 수,
 * subscriptionLimit: KIS WebSocket 구독 한도(기본 41), staleTimeout: 이 시간 동안 메시지가 없으면 멈춘 연결로 봐요(기본 60초).
 */
@ConfigurationProperties(prefix = "market.realtime")
public record RealtimeMarketProperties(boolean enabled, URI websocketUrl, Duration candleWatermark,
        Duration quoteTtl, int replaySize, int subscriptionLimit, Duration staleTimeout) {
    /** 하는 일: 구독 한도와 멈춤 판단 시간이 비었거나 0 이하이면 기본값(41개, 60초)을 채워요. */
    public RealtimeMarketProperties {
        subscriptionLimit = subscriptionLimit <= 0 ? 41 : subscriptionLimit;
        staleTimeout = staleTimeout == null || staleTimeout.isNegative() || staleTimeout.isZero()
                ? Duration.ofSeconds(60) : staleTimeout;
    }
}
