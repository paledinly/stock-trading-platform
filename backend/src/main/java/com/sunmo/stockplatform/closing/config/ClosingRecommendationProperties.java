/*
 * 장마감 추천 평가의 시간표와 최소 기준(관측 분 수, 확정봉 수, 최소 점수, 최소 거래대금)을 closing.recommendation 설정에서 읽어 와요.
 * 기본 시간표는 14:30 평가 시작, 15:15 특징값 동결(평가 기준시각), 15:20 가상 진입 마감이에요.
 * 장마감 추천 서비스와 정밀 평가기가 후보를 평가할 때 이 값을 기준으로 사용해요.
 */
package com.sunmo.stockplatform.closing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;

/**
 * 장마감 추천의 "언제 평가하고 언제까지 진입하는지"와 "얼마나 충분한 자료가 있어야 하는지"를 담는 설정이에요.
 * 시험 시간표처럼 시작 시각, 답안 제출 시각(동결), 퇴실 시각(진입 마감)을 정해 두고, 퇴실이 제출보다 빠른 시간표는 거부해요.
 */
@ConfigurationProperties(prefix = "closing.recommendation")
public record ClosingRecommendationProperties(
        int minimumCoverageMinutes,
        int minimumFinalCandles,
        BigDecimal minimumFinalScore,
        LocalTime evaluationStart,
        LocalTime featureFreezeAt,
        LocalTime entryDeadline,
        Duration candleFinalizationGrace,
        BigDecimal minimumDailyTradingValue,
        BigDecimal minimumFiveMinuteTradingValue) {

    /**
     * 하는 일: 거래대금 기준 없이 만드는 짧은 생성자예요(주로 테스트·기존 코드 호환용으로 보여요).
     * 받는 것: 최소 관측 분, 최소 확정봉 수, 최소 점수, 평가 시작, 동결 시각, 진입 마감, 봉 확정 유예시간이에요.
     * 돌려주는 것: 거래대금 기준은 기본값으로 채워진 설정 객체예요.
     */
    public ClosingRecommendationProperties(int coverage, int candles, BigDecimal score, LocalTime start,
            LocalTime freeze, LocalTime entry, Duration grace) {
        this(coverage, candles, score, start, freeze, entry, grace, null, null);
    }

    /**
     * 하는 일: 설정 파일 값을 받아 비어 있는 값은 기본값으로 채우고 앞뒤가 안 맞는 값을 막아요.
     * 받는 것: 설정 파일의 각 항목 값이에요.
     * 돌려주는 것: 검증된 설정 객체예요. 진입 마감이 동결 시각보다 빠르면 예외를 던져요.
     */
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public ClosingRecommendationProperties {
        minimumCoverageMinutes = minimumCoverageMinutes <= 0 ? 20 : minimumCoverageMinutes;
        minimumFinalCandles = minimumFinalCandles <= 0 ? 4 : minimumFinalCandles;
        minimumFinalScore = minimumFinalScore == null ? new BigDecimal("55") : minimumFinalScore;
        // 기본 시간표: 14:30 평가 시작, 15:15 동결(v9.1 평가 기준), 15:20 가상 진입 마감이에요.
        evaluationStart = evaluationStart == null ? LocalTime.of(14, 30) : evaluationStart;
        featureFreezeAt = featureFreezeAt == null ? LocalTime.of(15, 15) : featureFreezeAt;
        entryDeadline = entryDeadline == null ? LocalTime.of(15, 20) : entryDeadline;
        // 봉이 끝난 뒤 늦게 도착하는 자료를 얼마나 기다릴지예요. 음수면 0으로 봐요.
        candleFinalizationGrace = candleFinalizationGrace == null || candleFinalizationGrace.isNegative()
                ? Duration.ZERO : candleFinalizationGrace;
        // 하루 거래대금 10억 원, 5분 거래대금 2천만 원이 기본 최소 유동성 기준이에요.
        minimumDailyTradingValue = minimumDailyTradingValue == null ? new BigDecimal("1000000000") : minimumDailyTradingValue;
        minimumFiveMinuteTradingValue = minimumFiveMinuteTradingValue == null ? new BigDecimal("20000000") : minimumFiveMinuteTradingValue;
        if (minimumDailyTradingValue.signum() < 0 || minimumFiveMinuteTradingValue.signum() < 0)
            throw new IllegalArgumentException("Minimum liquidity must not be negative");
        if (entryDeadline.isBefore(featureFreezeAt))
            throw new IllegalArgumentException("entryDeadline must not be before featureFreezeAt");
    }
}
