/*
 * 정밀(Precision) 후보 실시간 구독 배정 규칙의 설정값(market.precision.*)을 담아요.
 * 최대 구독 수, 남겨 둘 여유 자리, 최소 유지 시간, 교체 점수 차이, 동결 시각의 기본값을 정해요.
 * marketwide 패키지의 PrecisionSubscriptionAllocator가 후보를 넣고 뺄 때 이 값을 따라요.
 */
package com.sunmo.stockplatform.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;

/**
 * 정밀 후보 자리 배정 규칙이에요. 인기 식당 좌석 규칙처럼 "한 번 앉으면 최소 15분은 앉아 있게 하고(minHold),
 * 새 손님 점수가 10점 이상 높아야 자리를 바꿔 주며(replaceMargin), 14:50 이후로는 자리를 고정(freezeAt)"해요.
 * maxSubscriptions 기본 28, reserve는 다른 용도로 비워 둘 자리 수(음수면 0)예요.
 */
@ConfigurationProperties(prefix = "market.precision")
public record PrecisionSubscriptionProperties(boolean enabled, int maxSubscriptions, int reserve,
        Duration minHold, BigDecimal replaceMargin, LocalTime freezeAt) {
    /** 하는 일: 빈 값이나 잘못된 값에 기본값을 채워요(최대 28, 유지 15분, 교체 차이 10점, 동결 14:50). */
    public PrecisionSubscriptionProperties {
        maxSubscriptions = maxSubscriptions <= 0 ? 28 : maxSubscriptions;
        reserve = Math.max(0, reserve);
        minHold = minHold == null ? Duration.ofMinutes(15) : minHold;
        replaceMargin = replaceMargin == null ? BigDecimal.TEN : replaceMargin;
        freezeAt = freezeAt == null ? LocalTime.of(14, 50) : freezeAt;
    }
}
