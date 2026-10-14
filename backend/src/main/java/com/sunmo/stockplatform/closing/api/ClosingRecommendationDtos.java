/*
 * 장마감 추천 REST API(웹이 URL로 요청하고 JSON으로 답을 받는 통로)가 주고받는 DTO(데이터를 옮기기만 하는 상자 객체)를 모아 둔 파일이에요.
 * 엔티티(DB 표 한 줄 객체)를 화면에 보여 줄 모양으로 바꾸는 from()/withRun() 변환도 함께 들어 있어요.
 * ClosingRecommendationController와 closing/application의 서비스들이 응답을 만들 때 사용해요.
 */
package com.sunmo.stockplatform.closing.api;

import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import com.sunmo.stockplatform.closing.domain.OvernightPerformance;
import com.sunmo.stockplatform.closing.domain.OvernightPositionDecision;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 장마감 추천 API 응답 상자(record)들을 한곳에 모은 묶음 클래스예요. 직접 객체로 만들지는 않아요.
 * 이삿짐 상자에 "주방용/침실용" 라벨을 붙여 한 창고에 모아 두듯, 응답 모양별 상자를 이 안에 모아 뒀어요.
 */
public final class ClosingRecommendationDtos {
    /** 묶음용 클래스라 객체를 만들지 못하게 막아 둔 생성자예요. */
    private ClosingRecommendationDtos() {
    }

    /**
     * 장마감 평가(generate) 한 번의 전체 결과예요. 원천 후보 수, 저장·관찰·제외 후보 수와 사유별 개수,
     * 평가 기준(criteria), 모든 후보의 평가 내역(evaluations), 실행 정보(runId, 모드, 완료 시각)를 담아요.
     */
    public record GenerateResponse(
            LocalDate recommendationDate,
            Instant generatedAt,
            int sourceDetections,
            int sourceBroadSnapshots,
            int storedCandidates,
            int watchCandidates,
            int excludedCandidates,
            Map<String, Integer> exclusionReasons,
            String strategyVersion,
            List<RecommendationResponse> candidates,
            Instant evaluationEnd,
            Map<String, Object> criteria,
            Map<String, Integer> decisionReasons,
            List<CandidateEvaluationResponse> evaluations,
            Long runId, String executionMode, Instant completedAt) {
        /**
         * 하는 일: 같은 결과에 실행 ID·모드·완료 시각만 채운 새 응답을 만들어요(record는 바꿀 수 없어 복사해요).
         * 받는 것: 실행 기록이에요. 돌려주는 것: 실행 정보가 채워진 새 GenerateResponse예요.
         */
        public GenerateResponse withRun(com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun run) {
            return new GenerateResponse(recommendationDate, generatedAt, sourceDetections, sourceBroadSnapshots,
                    storedCandidates, watchCandidates, excludedCandidates, exclusionReasons, strategyVersion,
                    candidates, evaluationEnd, criteria, decisionReasons, evaluations,
                    run.getId(), run.getExecutionMode(), run.getCompletedAt());
        }
    }

    /** 실행(run) 이력 목록의 한 줄이에요. 언제·어떤 모드·어떤 설정 해시와 데이터 버전으로 평가했는지 보여 줘요. */
    public record RunResponse(Long id, LocalDate recommendationDate, Instant generatedAt, Instant completedAt,
            Instant evaluatedAsOf, String executionMode, String strategyVersion, String settingsHash, String dataVersion) {
        /** 하는 일: 실행 엔티티를 응답 모양으로 옮겨 담아요. 받는 것: 실행 기록. 돌려주는 것: RunResponse예요. */
        public static RunResponse from(com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun run) {
            return new RunResponse(run.getId(), run.getRecommendationDate(), run.getGeneratedAt(), run.getCompletedAt(),
                    run.getEvaluatedAsOf(), run.getExecutionMode(), run.getStrategyVersion(), run.getSettingsHash(), run.getDataVersion());
        }
    }

    /**
     * 평가한 후보 한 종목의 상세예요. 점수·데이터 품질·확정봉 수·누락 특징값과 함께,
     * disposition(SELECTED/WATCH 등 분류)과 그 이유, 입력 준비 상태(dataReadiness)를 담아요.
     */
    public record CandidateEvaluationResponse(String stockCode, String stockName, String candidateSource,
            String scannerType, Instant observedAt, BigDecimal referencePrice, BigDecimal finalScore,
            BigDecimal opportunityScore, BigDecimal riskScore, String dataQuality, int finalCandles,
            int coverageMinutes, List<String> missingFeatures, String disposition, String decisionReason,
            String recommendationReason, String riskReason, String featureSnapshot,
            Map<String, Object> dataReadiness) { }

    /** 추천 명단 한 줄의 응답이에요. 순위, 종목, 출처, 매수 기준가, 점수, 이유, 실행 정보를 담아요. */
    public record RecommendationResponse(
            Long id,
            LocalDate recommendationDate,
            Instant generatedAt,
            int rank,
            String stockCode,
            String stockName,
            String market,
            String scannerType,
            String candidateSource,
            String dataQuality,
            int coverageMinutes,
            Long broadSnapshotId,
            String missingFeatures,
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
            String status, Long runId, String executionMode) {
        /**
         * 하는 일: 추천 엔티티를 응답 모양으로 바꿔요. BROAD 추천처럼 스캐너 유형이 없으면 null,
         * 실행이 연결되지 않은 옛 추천은 실행 모드를 LEGACY로 채워요.
         * 받는 것: 추천 엔티티예요. 돌려주는 것: RecommendationResponse예요.
         */
        public static RecommendationResponse from(ClosingRecommendation recommendation) {
            return new RecommendationResponse(
                    recommendation.getId(),
                    recommendation.getRecommendationDate(),
                    recommendation.getGeneratedAt(),
                    recommendation.getRank(),
                    recommendation.getStock().getStockCode(),
                    recommendation.getStock().getStockName(),
                    recommendation.getStock().getMarket().name(),
                    recommendation.getScannerType() == null ? null : recommendation.getScannerType().name(),
                    recommendation.getCandidateSource().name(),
                    recommendation.getDataQuality(),
                    recommendation.getCoverageMinutes(),
                    recommendation.getBroadSnapshot() == null ? null : recommendation.getBroadSnapshot().getId(),
                    recommendation.getMissingFeatures(),
                    recommendation.getCandidateObservedAt(),
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
                    recommendation.getStatus().name(),
                    recommendation.getRun() == null ? null : recommendation.getRun().getId(),
                    recommendation.getRun() == null ? "LEGACY" : recommendation.getRun().getExecutionMode());
        }
    }

    /** 다음 날 성과 갱신(track) 요청의 결과 요약이에요. 대상 추천 수, 완료·자료 부족 수, 목표/손절 비율과 각 성과를 담아요. */
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

    /**
     * 추천 한 건의 다음 거래일 성과 응답이에요. 다음 날 시·고·저·종가 수익률, 관측 범위와 결측 구간,
     * 가상 진입·청산 결과(비용 전/후), 비용 가정과 상태를 담아요. 실제 계좌 손익이 아니에요.
     */
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
            String calculationVersion,
            LocalDate expectedSessionDate, Instant sessionOpen, Instant sessionClose, Instant observedThrough,
            String missingIntervals, BigDecimal latestPrice, BigDecimal latestReturnRate,
            BigDecimal targetRate, BigDecimal stopRate, Long runId,
            Instant virtualEntryAt, BigDecimal virtualEntryPrice, Instant executionExitAt,
            BigDecimal executionExitPrice, BigDecimal grossReturnRate, BigDecimal netReturnRate,
            String executionExitReason, boolean executionAmbiguous, String executionModelVersion,
            String costAssumption, String costStatus) {
        /** 하는 일: 성과 엔티티와 연결된 추천 정보를 합쳐 응답 모양으로 바꿔요. 받는 것: 성과 엔티티. 돌려주는 것: OvernightPerformanceResponse예요. */
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
                    performance.getCalculationVersion(), performance.getExpectedSessionDate(),
                    performance.getSessionOpen(), performance.getSessionClose(), performance.getObservedThrough(),
                    performance.getMissingIntervals(), performance.getLatestPrice(), performance.getLatestReturnRate(),
                    performance.getTargetRate(), performance.getStopRate(),
                    recommendation.getRun() == null ? null : recommendation.getRun().getId(),
                    performance.getVirtualEntryAt(), performance.getVirtualEntryPrice(),
                    performance.getExecutionExitAt(), performance.getExecutionExitPrice(),
                    performance.getGrossReturnRate(), performance.getNetReturnRate(),
                    performance.getExecutionExitReason(), performance.isExecutionAmbiguous(),
                    performance.getExecutionModelVersion(), performance.getCostAssumption(),
                    performance.getCostStatus());
        }
    }

    /**
     * 과거 기간을 다시 돌려 본 백테스트 결과예요. 기간, 가상 추천 수, 승률·평균 수익률, 무결성 검사 결과,
     * 청산 전략별·추천 알고리즘별 요약과 개별 행을 담아요. 표본이 작으면 수익성 검증으로 보면 안 돼요.
     */
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

    /** 추천 알고리즘 하나의 백테스트 요약이에요(표본 수, 신뢰도 표시, 승률, 손익비, 종목 다양성 등). */
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

    /** 청산 전략 하나의 백테스트 요약이에요. 비용 반영 여부와 봉 안 순서가 모호한 건수도 담아요. */
    public record OvernightExitStrategySummary(
            String strategy,
            String label,
            int sampleSize,
            BigDecimal winRate,
            BigDecimal averageReturnRate,
            BigDecimal averageNetReturnRate,
            BigDecimal averageMaxDrawdownRate,
            BigDecimal targetHitRate,
            BigDecimal stopHitRate,
            int ambiguousCount,
            boolean costsApplied,
            String executionModelVersion) {
    }

    /** 백테스트의 개별 가상 추천 한 줄이에요. 신호 시각·가격, 가상 진입 시각, 다음 날 수익률과 목표/손절 도달 여부를 담아요. */
    public record OvernightBacktestRow(
            LocalDate recommendationDate,
            int rank,
            String stockCode,
            String stockName,
            String market,
            String scannerType,
            Instant detectedAt,
            BigDecimal signalPrice,
            Instant entryAt,
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

    /** 백테스트 자료의 무결성 검사 결과 요약이에요(통과·경고·실패 개수와 문제 목록). */
    public record BacktestIntegrityReport(
            String status,
            int totalChecks,
            int passedChecks,
            int warningChecks,
            int failedChecks,
            List<BacktestIntegrityIssue> issues) {
    }

    /** 무결성 검사에서 발견한 문제 하나예요(심각도, 분류, 날짜, 종목, 설명). */
    public record BacktestIntegrityIssue(
            String severity,
            String category,
            LocalDate recommendationDate,
            String stockCode,
            String stockName,
            String message,
            String detail) {
    }

    /** 보유 판단 평가 요청의 결과 요약이에요. 판단 종류별 개수(HOLD·DATA_PENDING 개수는 따로 없어요)와 각 판단을 담아요. */
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

    /** 보유 판단 한 건의 응답이에요. 판단 시각의 가격·VWAP·체결강도·이동평균과 판단 종류, 이유 JSON을 담아요. */
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
        /** 하는 일: 판단 엔티티와 연결된 추천 정보를 합쳐 응답 모양으로 바꿔요. 받는 것: 판단 엔티티. 돌려주는 것: OvernightPositionDecisionResponse예요. */
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
