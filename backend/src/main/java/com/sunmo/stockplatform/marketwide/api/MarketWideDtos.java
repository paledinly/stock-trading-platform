package com.sunmo.stockplatform.marketwide.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class MarketWideDtos {
        private MarketWideDtos() {
        }

        public record BroadScanResponse(
                        Instant scannedAt,
                        String market,
                        int requestedLimit,
                        int scannedCount,
                        int candidateCount,
                        UniverseResponse universe,
                        RegimeResponse regime,
                        List<CandidateResponse> candidates) {
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
                        boolean precisionEligible,
                        Instant quotedAt) {
        }
}
