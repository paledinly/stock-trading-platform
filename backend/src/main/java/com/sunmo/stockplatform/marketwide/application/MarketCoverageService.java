/*
 * 하루 동안 시장 전체 스캔이 얼마나 넓게(커버리지) 종목을 살펴봤는지 숫자로 정리하는 파일이에요.
 * 스캔 회차 수, 스냅샷 수집 비율, 정밀 구독 수, 감지 종목 수, 추천 출처(BROAD/PRECISION)별 성과를 한 응답으로 묶어요.
 * MarketWideScannerController의 커버리지 조회 API가 사용해요.
 */
package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.closing.domain.*;
import com.sunmo.stockplatform.closing.infrastructure.*;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.*;
import com.sunmo.stockplatform.marketwide.domain.*;
import com.sunmo.stockplatform.marketwide.infrastructure.*;
import com.sunmo.stockplatform.scanner.infrastructure.ScannerDetectionRepository;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 하루치 시장 커버리지 보고서를 만드는 서비스예요. 읽기 전용 트랜잭션(DB를 한 묶음으로 읽기만 하는 작업)으로 동작해요.
 * 하루 영업을 마친 가게가 "오늘 몇 명이 들어왔고, 몇 명이 물건을 봤고, 몇 명이 샀는지"를 집계표로 정리하는 것과 같아요.
 */
@Service
@Transactional(readOnly = true)
public class MarketCoverageService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private final StockRepository stocks;
    private final MarketBroadSnapshotRepository snapshots;
    private final PrecisionSubscriptionSessionRepository subscriptions;
    private final ScannerDetectionRepository detections;
    private final ClosingRecommendationRepository recommendations;
    private final OvernightPerformanceRepository performances;
    private final MarketWideScanRunRepository runs;
    private final com.sunmo.stockplatform.analytics.application.SummaryReadCache cache;

    /**
     * 하는 일: 캐시 없이(보관 시간 0) 서비스를 만드는 생성자예요. 테스트 등에서 쓰는 것으로 보여요.
     * 받는 것: 종목·스냅샷·구독·감지·추천·성과·스캔 실행 레포지토리(DB 창구)들.
     */
    public MarketCoverageService(StockRepository stocks, MarketBroadSnapshotRepository snapshots,
            PrecisionSubscriptionSessionRepository subscriptions, ScannerDetectionRepository detections,
            ClosingRecommendationRepository recommendations, OvernightPerformanceRepository performances,
            MarketWideScanRunRepository runs) {
        this(stocks, snapshots, subscriptions, detections, recommendations, performances, runs,
                new com.sunmo.stockplatform.analytics.application.SummaryReadCache(Duration.ZERO));
    }

    /**
     * 하는 일: 스프링이 쓰는 생성자예요. 계산 결과를 잠시 보관하는 캐시(같은 질문에 다시 계산하지 않게 기억해 두는 곳)까지 받아요.
     * 받는 것: 위 레포지토리들과 요약 캐시.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public MarketCoverageService(StockRepository stocks, MarketBroadSnapshotRepository snapshots,
            PrecisionSubscriptionSessionRepository subscriptions, ScannerDetectionRepository detections,
            ClosingRecommendationRepository recommendations, OvernightPerformanceRepository performances,
            MarketWideScanRunRepository runs, com.sunmo.stockplatform.analytics.application.SummaryReadCache cache) {
        this.stocks = stocks; this.snapshots = snapshots; this.subscriptions = subscriptions;
        this.detections = detections; this.recommendations = recommendations; this.performances = performances;
        this.runs = runs;
        this.cache = cache;
    }

    /**
     * 하는 일: 지정한 날짜(없으면 오늘)의 커버리지 보고서를 캐시에서 꺼내거나 새로 계산해요.
     * 받는 것: 날짜(null 가능).
     * 돌려주는 것: 커버리지 응답.
     */
    public CoverageResponse coverage(LocalDate date) {
        LocalDate target = date == null ? LocalDate.now(MARKET_ZONE) : date;
        return cache.get(new CoverageKey(target), () -> calculateCoverage(target));
    }
    /** 캐시에서 날짜별로 결과를 구분하기 위한 열쇠예요. */
    private record CoverageKey(LocalDate date) {}
    /**
     * 하는 일: 하루치 데이터를 모두 읽어 커버리지 지표를 계산해요.
     * 받는 것: 대상 날짜.
     * 돌려주는 것: 커버리지 응답(지표와 해석 주의 문구 포함).
     */
    private CoverageResponse calculateCoverage(LocalDate target) {
        // 준비: 종목 수, 스냅샷, 구독 이력, 추천, 성과, 스캔 회차, 감지 종목 수를 읽어요.
        long active = stocks.countByActiveTrue();
        long tradable = stocks.countByActiveTrueAndManagedFalseAndTradingHaltedFalseAndEtfFalseAndEtnFalse();
        List<BroadCoverageRow> latestSnapshots = snapshots.findLatestCoverage(target);
        List<PrecisionSubscriptionSession> subscriptionRows = subscriptions.findBySessionDateOrderByRequestedAtAsc(target);
        Instant measurementEnd = measurementEnd(target);
        List<ClosingRecommendation> recommendationRows = recommendations.findByRecommendationDateOrderByRankAsc(target);
        List<OvernightPerformance> performanceRows = performances.findByRecommendationDate(target);
        List<MarketWideScanRun> runRows = runs.findBySessionDateOrderByScheduledForAsc(target);
        Instant from = target.atStartOfDay(MARKET_ZONE).toInstant();
        Instant to = target.plusDays(1).atStartOfDay(MARKET_ZONE).toInstant();
        int detectionStocks = Math.toIntExact(detections.countDistinctStocks(from, to));

        // 처리: 수집 성공/부족 수, 구독 신청·활성 종목 수, 후보 제외 사유, 출처별 추천 수를 세요.
        int captured = latestSnapshots.size();
        int collected = (int) latestSnapshots.stream()
                .filter(row -> row.getCollectionStatus() == BroadSnapshotStatus.COLLECTED).count();
        int insufficient = captured - collected;
        Set<String> requestedCodes = subscriptionRows.stream().map(PrecisionSubscriptionSession::getStockCode)
                .collect(Collectors.toSet());
        Set<String> activatedCodes = subscriptionRows.stream().filter(row -> row.getActivatedAt() != null)
                .map(PrecisionSubscriptionSession::getStockCode).collect(Collectors.toSet());
        Map<String, Integer> exclusion = exclusionReasons(latestSnapshots, recommendationRows);
        int precisionRecommendations = countSource(recommendationRows, ClosingCandidateSource.PRECISION);
        int broadRecommendations = countSource(recommendationRows, ClosingCandidateSource.BROAD);

        // 결과: 비율은 "거래 가능 종목 수" 대비로 계산해요.
        // 스냅샷에는 ETF 등 거래 가능 집계에서 빠진 종목도 섞일 수 있어서 비율이 100%를 넘을 수도 있는 것으로 보여요.
        return new CoverageResponse(target, Instant.now(), active, tradable, runRows.size(),
                countRuns(runRows, MarketWideScanRun.Status.COMPLETED), countRuns(runRows, MarketWideScanRun.Status.FAILED),
                captured, rate(captured, tradable), captured, collected, insufficient, rate(collected, tradable),
                requestedCodes.size(), activatedCodes.size(), averageMinutes(subscriptionRows, measurementEnd),
                detectionStocks, broadRecommendations, precisionRecommendations, exclusion,
                List.of(performance(ClosingCandidateSource.BROAD, recommendationRows, performanceRows),
                        performance(ClosingCandidateSource.PRECISION, recommendationRows, performanceRows)),
                List.of("전체 종목 수는 과거 특정일 기준이 아니라 현재 종목 마스터 기준으로 계산됩니다.",
                        "랭킹 커버리지는 Broad 스냅샷으로 저장된 종목만 기준으로 하며, 랭킹에 들지 않아 저장되지 않은 종목은 집계할 수 없습니다.",
                        "성과 통계는 참고용 설명 지표이며 수수료, 세금, 슬리피지는 반영하지 않습니다."));
    }

    /**
     * 하는 일: 스냅샷에 잡힌 종목마다 "왜 최종 추천이 안 됐는지(또는 됐는지)" 사유를 하나 정해 개수를 세요.
     * 우선순위: BROAD 추천됨 → 정밀로 승격 → 시세 실패 → 데이터 부족 → 거래 불가 → 점수/개수 제한으로 탈락.
     * 받는 것: 종목별 최신 스냅샷 행, 그날 추천 목록.
     * 돌려주는 것: 사유 → 종목 수 맵.
     */
    private Map<String, Integer> exclusionReasons(Collection<BroadCoverageRow> values,
            List<ClosingRecommendation> recommendations) {
        Set<Long> broad = recommendations.stream().filter(r -> r.getCandidateSource() == ClosingCandidateSource.BROAD)
                .map(r -> r.getStock().getId()).collect(Collectors.toSet());
        Set<Long> precision = recommendations.stream().filter(r -> r.getCandidateSource() == ClosingCandidateSource.PRECISION)
                .map(r -> r.getStock().getId()).collect(Collectors.toSet());
        Map<String, Integer> result = new TreeMap<>();
        for (BroadCoverageRow row : values) {
            String reason = broad.contains(row.getStockId()) ? "RECOMMENDED_BROAD"
                    : precision.contains(row.getStockId()) ? "PROMOTED_TO_PRECISION"
                    : row.getCollectionStatus() == BroadSnapshotStatus.QUOTE_FAILED ? "QUOTE_FAILED"
                    : row.getDataQuality() == BroadSnapshotQuality.INSUFFICIENT ? "INSUFFICIENT_DATA"
                    : !tradable(row) ? "NOT_TRADABLE" : "SCORE_OR_LIMIT_FILTERED";
            result.merge(reason, 1, Integer::sum);
        }
        return Map.copyOf(result);
    }

    /**
     * 하는 일: 스냅샷 행의 종목이 거래 가능한지(활성, 관리·정지 아님, ETF/ETN 아님) 확인해요.
     * 받는 것: 커버리지 행.
     * 돌려주는 것: 거래 가능하면 true.
     */
    private boolean tradable(BroadCoverageRow row) {
        return row.getActive() && !row.getManaged() && !row.getTradingHalted() && !row.getEtf() && !row.getEtn();
    }

    /**
     * 하는 일: 한 추천 출처(BROAD 또는 PRECISION)의 다음 거래일 성과를 요약해요.
     * 완료(COMPLETED)된 성과만 평균·비율에 쓰고, 나머지는 미완료 개수로만 세요.
     * 받는 것: 추천 출처, 그날 추천 목록, 성과 목록.
     * 돌려주는 것: 추천 수, 완료/미완료 수, 상승 비율, 평균 수익률, 목표/손절 도달 비율을 담은 응답.
     */
    private SourcePerformanceResponse performance(ClosingCandidateSource source,
            List<ClosingRecommendation> recommendations, List<OvernightPerformance> rows) {
        Set<Long> ids = recommendations.stream().filter(r -> r.getCandidateSource() == source)
                .map(ClosingRecommendation::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<OvernightPerformance> relevant = rows.stream()
                .filter(p -> ids.contains(p.getRecommendation().getId())).toList();
        List<OvernightPerformance> completed = relevant.stream()
                .filter(p -> p.getStatus() == OvernightPerformanceStatus.COMPLETED).toList();
        return new SourcePerformanceResponse(source.name(), ids.size(), completed.size(), ids.size() - completed.size(),
                positiveRate(completed, OvernightPerformance::getCloseReturnRate),
                avg(completed, OvernightPerformance::getOpenReturnRate), avg(completed, OvernightPerformance::getCloseReturnRate),
                avg(completed, OvernightPerformance::getMaxReturnRate), avg(completed, OvernightPerformance::getMaxDrawdownRate),
                boolRate(completed, OvernightPerformance::isTargetHit), boolRate(completed, OvernightPerformance::isStopHit));
    }

    /**
     * 하는 일: 정밀 구독이 활성화된 뒤 평균 몇 분 유지됐는지 계산해요. 아직 안 끝난 구독은 측정 종료 시각까지로 쳐요.
     * 받는 것: 구독 세션 목록, 측정 종료 시각.
     * 돌려주는 것: 평균 분(소수 3자리), 활성 구독이 없으면 null.
     */
    private BigDecimal averageMinutes(List<PrecisionSubscriptionSession> rows, Instant end) {
        List<Long> values = rows.stream().filter(row -> row.getActivatedAt() != null)
                .map(row -> Duration.between(row.getActivatedAt(), row.getEndedAt() == null ? end : row.getEndedAt()).toMinutes())
                .map(value -> Math.max(0, value)).toList();
        return values.isEmpty() ? null : BigDecimal.valueOf(values.stream().mapToLong(Long::longValue).average().orElse(0))
                .setScale(3, RoundingMode.HALF_UP);
    }
    /**
     * 하는 일: 구독 시간 측정의 끝 시각을 정해요. 오늘 장중이면 지금, 아니면 그날 15:30이에요.
     * 받는 것: 대상 날짜.
     * 돌려주는 것: 측정 종료 시각.
     */
    private Instant measurementEnd(LocalDate date) {
        // ⚠️ 확인 필요: 장 마감을 15:30으로 고정했어요. 단축 장·마감 지연일은 반영되지 않아요.
        Instant close = date.atTime(15, 30).atZone(MARKET_ZONE).toInstant();
        Instant now = Instant.now();
        return date.equals(LocalDate.now(MARKET_ZONE)) && now.isBefore(close) ? now : close;
    }
    /**
     * 하는 일: 추천 목록에서 특정 출처의 개수를 세요.
     * 받는 것: 추천 목록, 출처.
     * 돌려주는 것: 개수.
     */
    private int countSource(List<ClosingRecommendation> rows, ClosingCandidateSource source) {
        return (int) rows.stream().filter(row -> row.getCandidateSource() == source).count();
    }
    /**
     * 하는 일: 스캔 회차 중 특정 상태(완료/실패)의 개수를 세요.
     * 받는 것: 회차 목록, 상태.
     * 돌려주는 것: 개수.
     */
    private int countRuns(List<MarketWideScanRun> rows, MarketWideScanRun.Status status) {
        return (int) rows.stream().filter(row -> row.getStatus() == status).count();
    }
    /**
     * 하는 일: 백분율을 소수 3자리로 계산해요.
     * 받는 것: 개수, 전체 수.
     * 돌려주는 것: 퍼센트 값, 전체가 0이면 null.
     */
    private BigDecimal rate(long count, long total) {
        return total == 0 ? null : BigDecimal.valueOf(count).multiply(bd("100"))
                .divide(BigDecimal.valueOf(total), 3, RoundingMode.HALF_UP);
    }
    /**
     * 하는 일: 어떤 수익률이 0보다 큰 성과의 비율(%)을 계산해요. 값이 없는 행도 분모에는 들어가요.
     * 받는 것: 성과 목록, 수익률을 꺼내는 함수.
     * 돌려주는 것: 퍼센트 값(목록이 비면 null).
     */
    private BigDecimal positiveRate(List<OvernightPerformance> rows,
            Function<OvernightPerformance, BigDecimal> getter) {
        long count = rows.stream().map(getter).filter(Objects::nonNull).filter(v -> v.signum() > 0).count();
        return rate(count, rows.size());
    }
    /**
     * 하는 일: 조건(목표 도달 등)을 만족하는 성과의 비율(%)을 계산해요.
     * 받는 것: 성과 목록, 조건.
     * 돌려주는 것: 퍼센트 값(목록이 비면 null).
     */
    private BigDecimal boolRate(List<OvernightPerformance> rows,
            java.util.function.Predicate<OvernightPerformance> predicate) {
        return rate(rows.stream().filter(predicate).count(), rows.size());
    }
    /**
     * 하는 일: 값이 있는 행만으로 평균을 소수 6자리로 계산해요.
     * 받는 것: 성과 목록, 값을 꺼내는 함수.
     * 돌려주는 것: 평균, 값이 하나도 없으면 null.
     */
    private BigDecimal avg(List<OvernightPerformance> rows, Function<OvernightPerformance, BigDecimal> getter) {
        List<BigDecimal> values = rows.stream().map(getter).filter(Objects::nonNull).toList();
        return values.isEmpty() ? null : values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }
    /** 문자열로 BigDecimal을 만드는 짧은 도우미예요. */
    private BigDecimal bd(String value) { return new BigDecimal(value); }
}
