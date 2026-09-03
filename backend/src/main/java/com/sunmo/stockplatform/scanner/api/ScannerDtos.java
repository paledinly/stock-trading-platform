package com.sunmo.stockplatform.scanner.api;

import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import com.sunmo.stockplatform.scanner.domain.ScannerSetting;
import com.sunmo.stockplatform.scanner.domain.ScannerType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class ScannerDtos {
    private ScannerDtos() {
    }

    public record SettingRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull ScannerType type,
            @NotNull @PositiveOrZero BigDecimal minChangeRate,
            @NotNull @PositiveOrZero BigDecimal minVolumeRatio,
            @NotNull @PositiveOrZero BigDecimal minFiveMinuteTradingValue,
            @NotNull @PositiveOrZero BigDecimal minDailyTradingValue,
            @NotNull @PositiveOrZero BigDecimal minPrice,
            boolean includeEtf,
            @Min(1) int cooldownSeconds,
            boolean active,
            @PositiveOrZero long version) {
    }

    public record SettingResponse(
            Long id,
            String name,
            ScannerType type,
            BigDecimal minChangeRate,
            BigDecimal minVolumeRatio,
            BigDecimal minFiveMinuteTradingValue,
            BigDecimal minDailyTradingValue,
            BigDecimal minPrice,
            boolean includeEtf,
            int cooldownSeconds,
            boolean active,
            long version) {
        public static SettingResponse from(ScannerSetting setting) {
            return new SettingResponse(
                    setting.getId(),
                    setting.getName(),
                    setting.getType(),
                    setting.getMinChangeRate(),
                    setting.getMinVolumeRatio(),
                    setting.getMinFiveMinuteTradingValue(),
                    setting.getMinDailyTradingValue(),
                    setting.getMinPrice(),
                    setting.isIncludeEtf(),
                    setting.getCooldownSeconds(),
                    setting.isActive(),
                    setting.getVersion());
        }
    }

    public record DetectionResponse(
            Long id,
            String eventId,
            String scannerType,
            String stockCode,
            String stockName,
            String market,
            Instant detectedAt,
            BigDecimal detectedPrice,
            BigDecimal fiveMinuteChangeRate,
            BigDecimal volumeRatio,
            long currentFiveMinuteVolume,
            BigDecimal currentFiveMinuteTradingValue,
            BigDecimal dailyTradingValue,
            BigDecimal momentumScore,
            BigDecimal opportunityScore,
            BigDecimal riskScore,
            String scoreVersion,
            String scoreBreakdown,
            String settingName,
            String settingSnapshot,
            String featureVersion,
            String featureSnapshot,
            String detectionReason) {
        public static DetectionResponse from(ScannerDetection detection) {
            return new DetectionResponse(
                    detection.getId(),
                    detection.getEventId().toString(),
                    detection.getType().name(),
                    detection.getStock().getStockCode(),
                    detection.getStock().getStockName(),
                    detection.getStock().getMarket().name(),
                    detection.getDetectedAt(),
                    detection.getDetectedPrice(),
                    detection.getChangeRate(),
                    detection.getVolumeRatio(),
                    detection.getCurrentVolume(),
                    detection.getCurrentValue(),
                    detection.getDailyValue(),
                    detection.getScore(),
                    detection.getOpportunityScore(),
                    detection.getRiskScore(),
                    detection.getScoreVersion(),
                    detection.getScoreBreakdown(),
                    detection.getSetting().getName(),
                    detection.getSettingSnapshot(),
                    detection.getFeatureVersion(),
                    detection.getFeatureSnapshot(),
                    detection.getDetectionReason());
        }
    }
}
