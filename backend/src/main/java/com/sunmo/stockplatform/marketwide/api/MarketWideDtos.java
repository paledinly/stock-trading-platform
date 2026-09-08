package com.sunmo.stockplatform.marketwide.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;

public final class MarketWideDtos {
        private MarketWideDtos() {
        }

        public record BroadScanResponse(
                        Instant scannedAt,
                        String market,
                        int requestedLimit,
                        int scannedCount,
                        int candidateCount,
                        boolean fallback,
                        List<RankingSourceResponse> rankingSources,
                        PrecisionAllocationResponse precisionAllocation,
                        UniverseResponse universe,
                        RegimeResponse regime,
                        List<CandidateResponse> candidates) {
        }

        public record RankingSourceResponse(String type, boolean success, int candidateCount, String error) {
        }

        public record PrecisionAllocationResponse(String state, Instant evaluatedAt, int capacity, int activeCount,
                        int remainingSlots, int reservedSlots, List<PrecisionAllocationItemResponse> allocations) {
        }

        public record PrecisionAllocationItemResponse(String stockCode, BigDecimal score, Instant addedAt,
                        Instant lastSeenAt, boolean awaitingAcknowledgement) {
        }

        public record UniverseResponse(
                        long activeStocks,
                        long tradableStocks,
                        int realtimeSubscriptionLimit,
                        int realtimeSubscriptionCount,
                        int realtimeSubscriptionRemaining) {
        }

        public record RegimeResponse(
                        String state,
                        BigDecimal averageChangeRate,
                        BigDecimal advanceRate,
                        BigDecimal declineRate,
                        BigDecimal averageTradingValue) {
        }

        public record CandidateResponse(
                        String stockCode,
                        String stockName,
                        String market,
                        BigDecimal currentPrice,
                        BigDecimal changeRate,
                        long accumulatedVolume,
                        BigDecimal accumulatedTradingValue,
                        BigDecimal broadScore,
                        String reason,
                        List<String> rankingSources,
                        Map<String, Integer> rankingRanks,
                        Long snapshotId,
                        String dataQuality,
                        boolean precisionEligible,
                        Instant quotedAt) {
        }

        public record SnapshotResponse(
                        Long id,
                        java.time.LocalDate sessionDate,
                        Instant capturedAt,
                        String stockCode,
                        String stockName,
                        String market,
                        BigDecimal currentPrice,
                        BigDecimal changeRate,
                        Long accumulatedVolume,
                        BigDecimal accumulatedTradingValue,
                        BigDecimal dayOpen,
                        BigDecimal dayHigh,
                        BigDecimal dayLow,
                        BigDecimal tradeStrength,
                        BigDecimal broadScore,
                        String rankingSources,
                        String dataQuality,
                        String collectionStatus,
                        String exclusionReason,
                        Instant quotedAt,
                        String sourceVersion) {
        }

        public record CoverageResponse(
                        LocalDate sessionDate,
                        Instant calculatedAt,
                        long activeUniverse,
                        long tradableUniverse,
                        int scheduledRuns,
                        int completedRuns,
                        int failedRuns,
                        int rankingCapturedStocks,
                        BigDecimal rankingCoverageRate,
                        int broadSnapshotStocks,
                        int broadCollectedStocks,
                        int broadInsufficientStocks,
                        BigDecimal broadCoverageRate,
                        int precisionRequestedStocks,
                        int precisionActivatedStocks,
                        BigDecimal averagePrecisionMinutes,
                        int precisionDetectionStocks,
                        int broadRecommendations,
                        int precisionRecommendations,
                        Map<String, Integer> exclusionReasons,
                        List<SourcePerformanceResponse> sourcePerformance,
                        List<String> limitations) {}

        public record SourcePerformanceResponse(
                        String source,
                        int recommendations,
                        int completed,
                        int dataMissing,
                        BigDecimal closeWinRate,
                        BigDecimal averageOpenReturn,
                        BigDecimal averageCloseReturn,
                        BigDecimal averageMaxReturn,
                        BigDecimal averageMaxDrawdown,
                        BigDecimal targetHitRate,
                        BigDecimal stopHitRate) {}
}
