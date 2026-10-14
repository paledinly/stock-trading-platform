/*
 * 장 시작 전 언제 WebSocket을 연결하고 장 마감 후 언제 끊을지 정하는 여유 시간 설정(market.realtime.session.*)이에요.
 * RealtimeSessionPolicy.connectionAllowed가 이 값으로 연결 허용 구간을 계산해요.
 */
package com.sunmo.stockplatform.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

/**
 * 실시간 연결 여유 시간이에요. 가게 문을 개점 1분 전에 열고 폐점 2분 뒤에 닫는 것처럼,
 * connectBeforeOpen(기본 1분) 전에 연결하고 disconnectAfterClose(기본 2분) 뒤에 끊어요.
 */
@ConfigurationProperties("market.realtime.session")
public record RealtimeSessionProperties(@DefaultValue("1m") Duration connectBeforeOpen,
        @DefaultValue("2m") Duration disconnectAfterClose) {
    /** 하는 일: 두 여유 시간이 0~1시간 사이인지 검사하고, 벗어나면 IllegalArgumentException을 던져요. */
    public RealtimeSessionProperties {
        if (connectBeforeOpen.isNegative() || disconnectAfterClose.isNegative()
                || connectBeforeOpen.compareTo(Duration.ofHours(1)) > 0
                || disconnectAfterClose.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("Realtime session margins must be between zero and one hour");
    }
}
