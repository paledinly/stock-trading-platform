package com.sunmo.stockplatform.backtest.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class BacktestDtos {
        private BacktestDtos() {
        }

        public record BacktestResponse(
                        String stockCode,
                        String stockName,
                        Instant from,
                        Instant to,
                        int evaluatedCandles,
                        int virtualDetections,
                        List<SettingSummary> summaries,
                        List<VirtualDetection> detections) {
        }

        public record BacktestableStock(
                        String stockCode,
                        String stockName,
                        String market,
                        long candleCount,
                        Instant firstCandleAt,
                        Instant lastCandleAt) {
        }

        public record SettingSummary(
                        Long settingId,
                        String settingName,
                        String scannerType,
                        long detections,
                        BigDecimal winRate5m,
                        BigDecimal winRate30m,
                        BigDecimal winRate60m,
                        BigDecimal averageReturn5m,
                        BigDecimal averageReturn30m,
                        BigDecimal averageReturn60m,
                        BigDecimal averageMaxReturn,
                        BigDecimal averageMaxDrawdown) {
        }

        public record VirtualDetection(
                        Long settingId,
                        String settingName,
                        String scannerType,
                        Instant detectedAt,
                        BigDecimal detectedPrice,
                        BigDecimal changeRate,
                        BigDecimal volumeRatio,
                        BigDecimal score,
                        String reason,
                        VirtualPerformance performance) {
        }

        public record VirtualPerformance(
                        BigDecimal return5m,
                        BigDecimal return30m,
                        BigDecimal return60m,
                        BigDecimal maxReturn,
                        BigDecimal maxDrawdown,
                        String status) {
        }
}
