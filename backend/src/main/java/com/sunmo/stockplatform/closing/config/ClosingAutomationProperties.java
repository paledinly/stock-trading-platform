/*
 * 장마감 추천 자동 실행(스케줄러)에 쓰는 설정값을 application 설정의 closing.automation 항목에서 읽어 와요.
 * 값이 비어 있으면 기본값을 채우고, 말이 안 되는 값(음수 목표 수익률 등)이면 시작할 때 바로 막아요.
 * ClosingRecommendationScheduler가 자동 추천 실행과 관찰 종목 실시간 구독 수를 정할 때 사용해요.
 */
package com.sunmo.stockplatform.closing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 장마감 자동 추천을 켤지, 몇 종목까지 뽑을지, 기회·위험 점수 기준과 목표·손절 비율을 담는 설정 묶음이에요.
 * 알람 시계의 설정 화면처럼 "켜기/끄기, 몇 개, 어느 기준"을 한곳에 모아 두고, 이상한 값은 처음부터 받지 않아요.
 */
@ConfigurationProperties(prefix = "closing.automation")
public record ClosingAutomationProperties(
        boolean enabled,
        int limit,
        BigDecimal minimumOpportunity,
        BigDecimal maximumRisk,
        BigDecimal targetRate,
        BigDecimal stopRate,
        Integer observationSubscriptionLimit) {

    /**
     * 하는 일: 비어 있는 값에 기본값을 채우고 잘못된 값을 검사해요.
     * 받는 것: 설정 파일에서 읽은 각 항목 값이에요(없으면 0 또는 null).
     * 돌려주는 것: 검증된 설정 객체예요. 값이 잘못되면 IllegalArgumentException을 던져요.
     */
    public ClosingAutomationProperties {
        // 추천 개수는 0 이하이면 10개, 아무리 커도 30개로 제한해요.
        limit = limit <= 0 ? 10 : Math.min(limit, 30);
        minimumOpportunity = minimumOpportunity == null ? new BigDecimal("35") : minimumOpportunity;
        maximumRisk = maximumRisk == null ? new BigDecimal("65") : maximumRisk;
        // 목표 +3%, 손절 -2%가 기본값이에요(퍼센트 단위).
        targetRate = targetRate == null ? new BigDecimal("3") : targetRate;
        stopRate = stopRate == null ? new BigDecimal("-2") : stopRate;
        // Unselected SELECTED/WATCH candidates kept on realtime for paper entry and next-session bars; 0 disables.
        // (추천에 뽑히지 않은 SELECTED/WATCH 후보도 가상 진입·다음 날 봉 관측을 위해 실시간 구독을 유지할 개수예요. 0이면 꺼져요.)
        observationSubscriptionLimit = observationSubscriptionLimit == null ? 10 : observationSubscriptionLimit;
        if (observationSubscriptionLimit < 0)
            throw new IllegalArgumentException("Invalid closing observation subscription limit");
        // 목표는 반드시 양수, 손절은 반드시 음수여야 의미가 맞아요.
        if (minimumOpportunity.signum() < 0 || maximumRisk.signum() < 0
                || targetRate.signum() <= 0 || stopRate.signum() >= 0)
            throw new IllegalArgumentException("Invalid closing automation thresholds");
    }
}
