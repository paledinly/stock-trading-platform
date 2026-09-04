package com.sunmo.stockplatform.closing.api;

import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import com.sunmo.stockplatform.closing.domain.OvernightPerformance;
import com.sunmo.stockplatform.closing.domain.OvernightPositionDecision;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ClosingRecommendationDtos {
    private ClosingRecommendationDtos() {
    }

    public record GenerateResponse(
            LocalDate recommendationDate,
            Instant generatedAt,
            int sourceDetections,
            int storedCandidates,
            String strategyVersion,
            List<RecommendationResponse> candidates) {
    }

    public record RecommendationResponse(
            Long id,
            LocalDate recommendationDate,
            Instant generatedAt,
            int rank,
            String stockCode,
            String stockName,
            String market,
            String scannerType,
            Instant detectedAt,
            BigDecimal buyReferencePrice,
            BigDecimal recommendationScore,
            BigDecimal opportunityScore,
            BigDecimal riskScore,
            BigDecimal dailyTradingValue,
            BigDecimal fiveMinuteChangeRate,
            BigDecimal volumeRatio,
            String recommendationReason,
            String riskReason,
            String strategyVersion,
            String status) {
        public static RecommendationResponse from(ClosingRecommendation recommendation) {
            return new RecommendationResponse(
                    recommendation.getId(),
                    recommendation.getRecommendationDate(),
                    recommendation.getGeneratedAt(),
                    recommendation.getRank(),
                    recommendation.getStock().getStockCode(),
                    recommendation.getStock().getStockName(),
                    recommendation.getStock().getMarket().name(),
                    recommendation.getScannerType().name(),
                    recommendation.getSourceDetection().getDetectedAt(),
                    recommendation.getBuyReferencePrice(),
                    recommendation.getRecommendationScore(),
                    recommendation.getOpportunityScore(),
                    recommendation.getRiskScore(),
                    recommendation.getDailyTradingValue(),
                    recommendation.getFiveMinuteChangeRate(),
                    recommendation.getVolumeRatio(),
                    recommendation.getRecommendationReason(),
                    recommendation.getRiskReason(),
                    recommendation.getStrategyVersion(),
                    recommendation.getStatus().name());
        }
    }

    public record TrackPerformanceResponse(
            LocalDate recommendationDate,
            Instant evaluatedAt,
            int recommendations,
            int completed,
            int dataMissing,
            BigDecimal targetRate,
            BigDecimal stopRate,
            String calculationVersion,
            List<OvernightPerformanceResponse> performances) {
    }

    public record OvernightPerformanceResponse(
            Long id,
            Long recommendationId,
            LocalDate recommendationDate,
            String stockCode,
            String stockName,
            int rank,
            BigDecimal buyReferencePrice,
            LocalDate nextTradingDate,
            Instant evaluatedAt,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            BigDecimal openReturnRate,
            BigDecimal closeReturnRate,
            BigDecimal maxReturnRate,
            BigDecimal maxDrawdownRate,
            boolean targetHit,
            boolean stopHit,
            String status,
            String calculationVersion) {
        public static OvernightPerformanceResponse from(OvernightPerformance performance) {
            ClosingRecommendation recommendation = performance.getRecommendation();
            return new OvernightPerformanceResponse(
                    performance.getId(),
                    recommendation.getId(),
                    recommendation.getRecommendationDate(),
                    recommendation.getStock().getStockCode(),
                    recommendation.getStock().getStockName(),
                    recommendation.getRank(),
                    recommendation.getBuyReferencePrice(),
                    performance.getNextTradingDate(),
                    performance.getEvaluatedAt(),
                    performance.getOpenPrice(),
                    performance.getHighPrice(),
                    performance.getLowPrice(),
                    performance.getClosePrice(),
                    performance.getOpenReturnRate(),
                    performance.getCloseReturnRate(),
                    performance.getMaxReturnRate(),
                    performance.getMaxDrawdownRate(),
                    performance.isTargetHit(),
                    performance.isStopHit(),
                    performance.getStatus().name(),
                    performance.getCalculationVersion());
        }
    }

    public record OvernightBacktestResponse(
            LocalDate from,
            LocalDate to,
            int tradingDays,
            int virtualRecommendations,
            int completed,
            int dataMissing,
            BigDecimal winRateOpen,
            BigDecimal winRateClose,
            BigDecimal averageOpenReturn,
            BigDecimal averageCloseReturn,
            BigDecimal averageMaxReturn,
            BigDecimal averageMaxDrawdown,
            BigDecimal targetRate,
            BigDecimal stopRate,
            String calculationVersion,
            BacktestIntegrityReport integrity,
            List<OvernightExitStrategySummary> strategySummaries,
            List<RecommendationAlgorithmSummary> algorithmSummaries,
            List<OvernightBacktestRow> rows) {
    }

    public record RecommendationAlgorithmSummary(
            String algorithm,
            String label,
            int sampleSize,
            int completed,
            int dataMissing,
            String confidence,
            BigDecimal winRateOpen,
            BigDecimal winRateClose,
            BigDecimal averageOpenReturn,
            BigDecimal averageCloseReturn,
            BigDecimal averageMaxReturn,
            BigDecimal averageMaxDrawdown,
            BigDecimal targetHitRate,
            BigDecimal stopHitRate,
            BigDecimal profitFactor,
            int uniqueStocks,
            BigDecimal maxDateConcentrationRate,
            boolean recommendedDefault) {
    }

    public record OvernightExitStrategySummary(
            String strategy,
            String label,
            int sampleSize,
            BigDecimal winRate,
            BigDecimal averageReturnRate,
            BigDecimal averageMaxDrawdownRate,
            BigDecimal targetHitRate,
            BigDecimal stopHitRate,
            int ambiguousCount) {
    }

    public record OvernightBacktestRow(
            LocalDate recommendationDate,
            int rank,
            String stockCode,
            String stockName,
            String market,
            String scannerType,
            Instant detectedAt,
            BigDecimal buyReferencePrice,
            BigDecimal recommendationScore,
            BigDecimal opportunityScore,
            BigDecimal riskScore,
            LocalDate nextTradingDate,
            BigDecimal openReturnRate,
            BigDecimal closeReturnRate,
            BigDecimal maxReturnRate,
            BigDecimal maxDrawdownRate,
            boolean targetHit,
            boolean stopHit,
            String status) {
    }

    public record BacktestIntegrityReport(
            String status,
            int totalChecks,
            int passedChecks,
            int warningChecks,
            int failedChecks,
            List<BacktestIntegrityIssue> issues) {
    }

    public record BacktestIntegrityIssue(
            String severity,
            String category,
            LocalDate recommendationDate,
            String stockCode,
            String stockName,
            String message,
            String detail) {
    }

    public record DecisionEvaluationResponse(
            LocalDate recommendationDate,
            Instant evaluatedAt,
            int evaluated,
            int extendHold,
            int takeProfit,
            int sellWarning,
            int stopLoss,
            String calculationVersion,
            List<OvernightPositionDecisionResponse> decisions) {
    }

    public record OvernightPositionDecisionResponse(
            Long id,
            Long recommendationId,
            LocalDate recommendationDate,
            String stockCode,
            String stockName,
            int rank,
            BigDecimal buyReferencePrice,
            Instant evaluatedAt,
            BigDecimal currentPrice,
            BigDecimal returnRate,
            BigDecimal vwap,
            BigDecimal vwapDistanceRate,
            BigDecimal tradeStrength,
            BigDecimal ma5,
            BigDecimal ma20,
            BigDecimal ma60,
            boolean targetHit,
            boolean stopHit,
            String decision,
            String reasonJson,
            String calculationVersion) {
        public static OvernightPositionDecisionResponse from(OvernightPositionDecision decision) {
            ClosingRecommendation recommendation = decision.getRecommendation();
            return new OvernightPositionDecisionResponse(
                    decision.getId(),
                    recommendation.getId(),
                    recommendation.getRecommendationDate(),
                    recommendation.getStock().getStockCode(),
                    recommendation.getStock().getStockName(),
                    recommendation.getRank(),
                    recommendation.getBuyReferencePrice(),
                    decision.getEvaluatedAt(),
                    decision.getCurrentPrice(),
                    decision.getReturnRate(),
                    decision.getVwap(),
                    decision.getVwapDistanceRate(),
                    decision.getTradeStrength(),
                    decision.getMa5(),
                    decision.getMa20(),
                    decision.getMa60(),
                    decision.isTargetHit(),
                    decision.isStopHit(),
                    decision.getDecision().name(),
                    decision.getReasonJson(),
                    decision.getCalculationVersion());
        }
    }
}
