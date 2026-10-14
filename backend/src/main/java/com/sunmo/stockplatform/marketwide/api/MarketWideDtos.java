/*
 * 전체 시장 스캔 API가 돌려주는 응답 모양(DTO, 데이터를 담아 나르는 상자)을 한곳에 모아 둬요.
 * 스캔 결과, 수집 요약, 순위 출처, 정밀 구독 배정, 시장 분위기, 후보, 커버리지 응답이 들어 있어요.
 * MarketWideScannerController와 marketwide.application 서비스들이 만들어 웹 화면에 보내요.
 */
package com.sunmo.stockplatform.marketwide.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;

/**
 * 전체 시장 스캔 응답용 레코드들을 묶어 두는 보관함 클래스예요.
 * 택배 상자 규격표를 한 서랍에 모아 두듯, 응답 모양만 정의하고 계산은 하지 않아요.
 */
public final class MarketWideDtos {
        /** 객체를 만들 필요가 없는 보관함 클래스라서 생성자를 막아 뒀어요. */
        private MarketWideDtos() {
        }

        /**
         * 스캔 한 번의 전체 결과예요.
         * 스캔 시각, 시장, 요청 수, 훑어본 수, 후보 수, 대체 경로(fallback) 사용 여부와
         * 순위 출처·정밀 구독 배정·종목군(Universe)·시장 분위기·후보·수집 요약을 담아요.
         */
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
                        List<CandidateResponse> candidates,
                        CollectionSummary collection) {
        }

        /**
         * 시세 수집 과정 요약이에요.
         * 상세 시세 조회 예산, REST 조회·실패 수, 데이터 부족 종목 수, 출처별 개수,
         * 가장 오래된 데이터 나이(초), KIS 요청 횟수를 담아요.
         */
        public record CollectionSummary(int detailQuoteBudget, int restLookups, int restFailures,
                        int insufficientCount, Map<String, Integer> dataSources, long maxDataAgeSeconds,
                        Map<String, Long> kisRequests) { }

        /** 순위표(랭킹 API) 하나의 조회 결과예요: 종류, 성공 여부, 가져온 후보 수, 오류 메시지. */
        public record RankingSourceResponse(String type, boolean success, int candidateCount, String error) {
        }

        /**
         * 정밀 감시용 실시간 구독(특정 종목 체결을 계속 받아 보는 신청) 배정 현황이에요.
         * 상태, 평가 시각, 전체 수용량, 사용 중 수, 남은 칸, 설정으로 남겨 둔 예비 칸 수, 배정 목록을 담아요.
         */
        public record PrecisionAllocationResponse(String state, Instant evaluatedAt, int capacity, int activeCount,
                        int remainingSlots, int reservedSlots, List<PrecisionAllocationItemResponse> allocations) {
        }

        /**
         * 정밀 구독 배정 한 건이에요: 종목 코드, 점수, 추가 시각, 마지막으로 후보에 보인 시각,
         * 그리고 다른 종목과의 교체가 아직 확정되지 않아 기다리는 중인지 여부를 담아요.
         */
        public record PrecisionAllocationItemResponse(String stockCode, BigDecimal score, Instant addedAt,
                        Instant lastSeenAt, boolean awaitingAcknowledgement) {
        }

        /** 살펴볼 종목군(Universe) 정보예요: 활성·거래 가능 종목 수와 실시간 구독 한도·사용 수·남은 수. */
        public record UniverseResponse(
                        long activeStocks,
                        long tradableStocks,
                        int realtimeSubscriptionLimit,
                        int realtimeSubscriptionCount,
                        int realtimeSubscriptionRemaining) {
        }

        /** 시장 분위기(Regime) 요약이에요: 상태, 평균 등락률, 상승·하락 종목 비율, 평균 거래대금. */
        public record RegimeResponse(
                        String state,
                        BigDecimal averageChangeRate,
                        BigDecimal advanceRate,
                        BigDecimal declineRate,
                        BigDecimal averageTradingValue) {
        }

        /**
         * BROAD 후보 종목 한 개의 응답이에요.
         * 종목 정보와 시세, BROAD 점수(상승 확률이 아니에요), 선정 이유, 순위 출처·등수,
         * 저장된 스냅샷 id, 데이터 품질, 정밀 감시 대상 가능 여부, 시세 관측 시각과 출처를 담아요.
         */
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
                        Instant quotedAt,
                        String quoteSource) {
        }
        /**
         * 거래일 하루의 커버리지(시장을 얼마나 넓게 살펴봤는지) 집계예요.
         * 스캔 회차 수, 순위·BROAD 수집 종목 수와 비율, 정밀 구독 신청·활성 수와 평균 시간,
         * 추천 수, 제외 사유별 개수, 출처별 성과, 해석 시 주의할 한계를 담아요.
         */
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

        /**
         * 추천 출처별 사후 성과 요약이에요.
         * 추천 수, 추적 완료·데이터 누락 수, 종가 기준 승률, 평균 시가·종가·최대 수익률,
         * 평균 최대 낙폭, 목표가·손절가 도달 비율을 담아요. 표본이 적으면 검증된 수익성으로 보면 안 돼요.
         */
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
