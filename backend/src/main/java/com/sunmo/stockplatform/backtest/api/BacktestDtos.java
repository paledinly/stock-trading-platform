/*
 * 백테스트 API가 주고받는 응답 모양(DTO: 데이터를 옮기기 위한 단순한 값 묶음)을 한곳에 모아 둔 파일이에요.
 * BacktestService가 만들고 BacktestController가 JSON으로 돌려줘요.
 */
package com.sunmo.stockplatform.backtest.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 백테스트 응답 양식 모음이에요. 성적표 양식처럼 항목 이름만 정해 두고, 값은 BacktestService가 채워요.
 * 수익률·승률 값은 퍼센트(%)이고, 모두 가상 탐지 기준이라 실제 체결·비용이 반영되지 않았어요.
 */
public final class BacktestDtos {
        /** 객체로 만들 일이 없는 묶음 클래스라 생성자를 막아 둬요. */
        private BacktestDtos() {
        }

        /**
         * 백테스트 한 번의 결과예요. evaluatedCandles: 구간 안에서 평가한 확정봉 수,
         * virtualDetections: 전체 가상 탐지 수, summaries: 설정별 요약, detections: 최근 가상 탐지(limit개까지).
         */
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

        /** 백테스트 가능한 종목 한 줄이에요. 확정 5분봉 개수와 첫·마지막 봉 시각을 담아요. */
        public record BacktestableStock(
                        String stockCode,
                        String stockName,
                        String market,
                        long candleCount,
                        Instant firstCandleAt,
                        Instant lastCandleAt) {
        }

        /**
         * Scanner 설정 하나의 성적 요약이에요. winRate는 수익률이 0보다 큰 탐지 비율(%),
         * averageReturn은 평균 수익률(%), averageMaxReturn/averageMaxDrawdown은 이후 60분 안 최고/최저가 기준 평균(%)이에요.
         */
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

        /**
         * 가상 탐지 한 건이에요. detectedAt은 봉이 끝난 시각(봉 시작 + 5분), detectedPrice는 그 봉의 종가예요.
         * reason은 판단 근거 JSON 문자열이에요.
         */
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

        /**
         * 가상 탐지 이후 성과예요. 5m/30m/60m은 이후 1·6·12번째 확정봉 종가 기준 수익률(%)이에요.
         * status는 60분치 봉이 모두 있으면 COMPLETED, 모자라면 PARTIAL이에요.
         */
        public record VirtualPerformance(
                        BigDecimal return5m,
                        BigDecimal return30m,
                        BigDecimal return60m,
                        BigDecimal maxReturn,
                        BigDecimal maxDrawdown,
                        String status) {
        }
}
