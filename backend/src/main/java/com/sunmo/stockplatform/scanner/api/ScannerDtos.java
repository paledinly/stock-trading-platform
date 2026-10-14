/*
 * 스캐너 API가 주고받는 데이터 모양(DTO, 데이터를 담아 나르는 상자)을 모아 둔 파일이에요.
 * 설정 요청·응답과 감지 응답을 record로 정의해요.
 * ScannerController와 ScannerQueryService가 써요.
 */
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

/**
 * 스캐너 API용 DTO들을 한곳에 묶어 두는 보관함 클래스예요.
 * 택배 상자 규격표를 한 장에 모아 둔 서류철이라고 생각하면 돼요.
 */
public final class ScannerDtos {
    /**
     * 보관함 용도라 객체를 만들지 못하게 막아 둔 생성자예요.
     */
    private ScannerDtos() {
    }

    /**
     * 설정을 만들거나 고칠 때 받는 요청이에요.
     * 최소 변화율(%), 최소 거래량 배수, 최소 5분·당일 거래대금, 최소 가격, ETF 포함 여부,
     * 쿨다운(같은 종목을 다시 알리기 전에 쉬는 시간, 초), 활성 여부를 담아요.
     * version은 수정할 때 다른 곳에서 먼저 바꿨는지 확인하는 번호예요(생성 때는 쓰지 않아요).
     */
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

    /**
     * 설정을 화면에 돌려줄 때 쓰는 응답이에요.
     * 엔티티(DB 표 한 줄을 그대로 옮긴 객체)의 값을 그대로 옮겨 담아요.
     */
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
        /**
         * 하는 일: 설정 엔티티를 응답으로 바꿔요.
         * 받는 것: setting(설정 엔티티).
         * 돌려주는 것: SettingResponse.
         */
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

    /**
     * 감지 결과 한 건을 화면에 돌려줄 때 쓰는 응답이에요.
     * 감지 시각·가격·지표·점수와, 그때의 설정/특징값 스냅샷(그 순간 값을 찍어 둔 사진)을 함께 담아요.
     * fiveMinuteChangeRate는 이름과 달리 약 30분 기준 변화율이에요(ScannerEvaluator.calculate 참고).
     */
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
        /**
         * 하는 일: 감지 엔티티를 응답으로 바꿔요.
         * 받는 것: detection(감지 엔티티).
         * 돌려주는 것: DetectionResponse. 종목·설정은 지연 로딩이라 트랜잭션(DB 작업 묶음) 안에서 불러야 해요.
         */
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
