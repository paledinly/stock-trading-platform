package com.sunmo.stockplatform.closing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;

@ConfigurationProperties(prefix = "closing.recommendation")
public record ClosingRecommendationProperties(
        int minimumCoverageMinutes,
        int minimumFinalCandles,
        BigDecimal minimumFinalScore,
        LocalTime evaluationStart,
        LocalTime featureFreezeAt,
        LocalTime entryDeadline,
        Duration candleFinalizationGrace) {

    public ClosingRecommendationProperties {
        minimumCoverageMinutes = minimumCoverageMinutes <= 0 ? 20 : minimumCoverageMinutes;
        minimumFinalCandles = minimumFinalCandles <= 0 ? 4 : minimumFinalCandles;
        minimumFinalScore = minimumFinalScore == null ? new BigDecimal("55") : minimumFinalScore;
        evaluationStart = evaluationStart == null ? LocalTime.of(14, 30) : evaluationStart;
        featureFreezeAt = featureFreezeAt == null ? LocalTime.of(15, 0) : featureFreezeAt;
        entryDeadline = entryDeadline == null ? LocalTime.of(15, 20) : entryDeadline;
        candleFinalizationGrace = candleFinalizationGrace == null || candleFinalizationGrace.isNegative()
                ? Duration.ofSeconds(10) : candleFinalizationGrace;
        if (entryDeadline.isBefore(featureFreezeAt))
            throw new IllegalArgumentException("entryDeadline must not be before featureFreezeAt");
    }
}
