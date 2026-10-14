/*
 * 감지 성과 API(외부에서 서버 기능을 부르는 창구)가 화면에 돌려주는 응답 모양을 모아 둔 파일이에요.
 * 감지 한 건의 성과 상세와 기간 통계(승률, 평균 수익률, 시간대·조합별 성과)를 담아요.
 * ScannerAnalyticsController와 ScannerAnalyticsService가 이 응답 모양을 써요.
 */
package com.sunmo.stockplatform.analytics.api;

import com.sunmo.stockplatform.analytics.domain.DetectionPerformance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 성과 관련 응답 DTO(데이터를 옮기기만 하는 상자)들을 한곳에 모아 둔 묶음 클래스예요.
 * 택배 상자 규격표처럼 "어떤 칸에 무엇을 담아 보낼지"만 정해 두고, 계산은 하지 않아요.
 */
public final class PerformanceDtos {
        /** 묶음용 클래스라 객체를 만들지 못하게 막아 둔 생성자예요. */
        private PerformanceDtos() {
        }

        /**
         * 감지 한 건의 성과 상세 응답이에요. 가격, 감지 가격 대비 수익률(%), 관측 시각, 복구 사용 여부, 확정 정보를 담아요.
         * 경주 한 번의 구간별 기록지 한 장이라고 생각하면 돼요.
         */
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
                /**
                 * 하는 일: 성과 엔티티(DB 한 줄과 짝지어진 객체)를 응답 모양으로 옮겨 담아요.
                 * 받는 것: 성과 엔티티. 돌려주는 것: 성과 응답이에요.
                 */
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

        /**
         * 기간 통계 응답이에요. 건수, 승률·평균 수익률, 목표/손절 요약, 시간대별·조합별 성과, 전체 요약을 담아요.
         * 학기 성적표처럼 여러 기록을 모아 한 장으로 보여 줘요. 과거 기록일 뿐 상승 확률이 아니에요.
         */
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

        /**
         * 목표 수익률·손절 수익률 도달 건수와 비율 요약이에요. expectancy는 평균 마감 수익률이에요.
         * 이름의 "first"와 달리 어느 쪽이 먼저 닿았는지는 구분하지 않아요(서비스의 targetStop 참고).
         */
        public record TargetStopSummary(
                        long sampleSize,
                        long targetFirst,
                        long stopFirst,
                        long neither,
                        BigDecimal targetFirstRate,
                        BigDecimal stopFirstRate,
                        BigDecimal expectancy) {
        }

        /** 감지 시각의 "시"(예: 10:00)별 성과 한 칸이에요. 시간표의 한 교시 성적처럼 보면 돼요. */
        public record TimeBucketResponse(
                        String bucket,
                        long sampleSize,
                        BigDecimal winRate,
                        BigDecimal averageReturn,
                        BigDecimal averageMaxReturn,
                        BigDecimal averageMaxDrawdown) {
        }

        /** 스캐너 종류 × 기회점수 구간 × 위험점수 구간 조합 하나의 성과와 신뢰도(표본 수 기준)예요. */
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

        /** 기간 전체의 과거 성과 요약이에요. enoughSamples는 표본 수가 최소 표본 수 이상인지 알려 줘요. */
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
