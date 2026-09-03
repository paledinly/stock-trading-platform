package com.sunmo.stockplatform.analytics.api;

import com.sunmo.stockplatform.analytics.domain.DetectionPerformance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class PerformanceDtos {
        private PerformanceDtos() {
        }

        public record PerformanceResponse(
                        Long detectionId,
                        String stockCode,
                        String status,
                        String calculationVersion,
                        BigDecimal detectedPrice,
                        BigDecimal price5m,
                        BigDecimal price10m,
                        BigDecimal price30m,
                        BigDecimal price60m,
                        BigDecimal closePrice,
                        BigDecimal return5m,
                        BigDecimal return10m,
                        BigDecimal return30m,
                        BigDecimal return60m,
                        BigDecimal returnClose,
                        BigDecimal highestPrice,
                        BigDecimal lowestPrice,
                        BigDecimal maxReturn,
                        BigDecimal maxDrawdown,
                        BigDecimal mfe,
                        BigDecimal mae,
                        Instant observed5mAt,
                        Instant observed10mAt,
                        Instant observed30mAt,
                        Instant observed60mAt,
                        Instant closeObservedAt,
                        boolean recoveryUsed,
                        String finalizationReason,
                        Instant finalizedAt) {
                public static PerformanceResponse from(DetectionPerformance performance) {
                        return new PerformanceResponse(
                                        performance.getDetectionId(),
                                        performance.getDetection().getStock().getStockCode(),
                                        performance.getStatus().name(),
                                        performance.getCalculationVersion(),
                                        performance.getDetection().getDetectedPrice(),
                                        performance.getPrice5m(),
                                        performance.getPrice10m(),
                                        performance.getPrice30m(),
                                        performance.getPrice60m(),
                                        performance.getClosePrice(),
                                        performance.getReturn5m(),
                                        performance.getReturn10m(),
                                        performance.getReturn30m(),
                                        performance.getReturn60m(),
                                        performance.getReturnClose(),
                                        performance.getHighestPrice(),
                                        performance.getLowestPrice(),
                                        performance.getMaxReturn(),
                                        performance.getMaxDrawdown(),
                                        performance.getMfe(),
                                        performance.getMae(),
                                        performance.getObserved5mAt(),
                                        performance.getObserved10mAt(),
                                        performance.getObserved30mAt(),
                                        performance.getObserved60mAt(),
                                        performance.getCloseObservedAt(),
                                        performance.isRecoveryUsed(),
                                        performance.getFinalizationReason(),
                                        performance.getFinalizedAt());
                }
        }

        public record AnalyticsResponse(
                        long total,
                        long completed,
                        long dataMissing,
                        BigDecimal winRate5m,
                        BigDecimal winRateClose,
                        BigDecimal averageReturn5m,
                        BigDecimal averageReturn10m,
                        BigDecimal averageReturn30m,
                        BigDecimal averageReturn60m,
                        BigDecimal averageReturnClose,
                        String calculationVersion,
                        BigDecimal targetRate,
                        BigDecimal stopRate,
                        TargetStopSummary targetStop,
                        List<TimeBucketResponse> timeBuckets,
                        List<SignalCombinationResponse> signalCombinations,
                        HistoricalEdgeResponse historicalEdge,
                        int minimumSampleSize) {
        }

        public record TargetStopSummary(
                        long sampleSize,
                        long targetFirst,
                        long stopFirst,
                        long neither,
                        BigDecimal targetFirstRate,
                        BigDecimal stopFirstRate,
                        BigDecimal expectancy) {
        }

        public record TimeBucketResponse(
                        String bucket,
                        long sampleSize,
                        BigDecimal winRate,
                        BigDecimal averageReturn,
                        BigDecimal averageMaxReturn,
                        BigDecimal averageMaxDrawdown) {
        }

        public record SignalCombinationResponse(
                        String scannerType,
                        String opportunityBand,
                        String riskBand,
                        long sampleSize,
                        BigDecimal winRate,
                        BigDecimal averageReturn,
                        BigDecimal averageMaxReturn,
                        BigDecimal averageMaxDrawdown,
                        String confidence) {
        }

        public record HistoricalEdgeResponse(
                        long sampleSize,
                        BigDecimal winRate,
                        BigDecimal averageReturn,
                        BigDecimal expectancy,
                        BigDecimal averageMfe,
                        BigDecimal averageMae,
                        String confidence,
                        boolean enoughSamples) {
        }
}
