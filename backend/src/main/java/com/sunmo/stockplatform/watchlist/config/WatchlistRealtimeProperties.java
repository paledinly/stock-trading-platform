/*
 * 관심종목 실시간 고정 관련 설정값(watchlist.realtime.*)을 읽어 오는 파일이에요.
 * 지금은 동시에 실시간 고정할 수 있는 최대 종목 수(max-pinned, 기본 10)만 있어요.
 * WatchlistService가 고정 한도를 확인할 때 사용해요.
 */
package com.sunmo.stockplatform.watchlist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 실시간 고정 한도 설정이에요.
 * 주차장 입구의 "최대 N대" 안내판처럼, 관심종목에서 실시간 고정할 수 있는 서로 다른 종목 수의 상한을 정해요.
 */
@ConfigurationProperties(prefix = "watchlist.realtime")
public record WatchlistRealtimeProperties(int maxPinned) {
    /** 설정값을 검사해요. 한도가 음수면 서버 시작 시 바로 실패하게 해요. */
    public WatchlistRealtimeProperties {
        if (maxPinned < 0) throw new IllegalArgumentException("watchlist.realtime.max-pinned must be non-negative");
    }
}
