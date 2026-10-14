/*
 * 저장된 Scanner 탐지와 5분봉으로 과거 기간의 마감 추천을 다시 만들어 보고, 다음날 성과를 여러 청산 방식으로 계산하는 백테스트(과거 자료로 전략을 시험해 보는 것) 서비스예요.
 * 추천 알고리즘 몇 가지(기존 탐지 점수, 이평선 제거, 이평선 포함, 보수형)도 같은 기간에서 나란히 비교해요. 결과는 표본 내 비교일 뿐 수익성 검증이 아니에요.
 * 추천 API(ClosingRecommendationController)의 백테스트 요청이 사용해요.
 */
package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.OvernightBacktestResponse;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.OvernightBacktestRow;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.OvernightExitStrategySummary;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.RecommendationAlgorithmSummary;
import com.sunmo.stockplatform.closing.application.BacktestIntegrityService.BacktestEvaluation;
import com.sunmo.stockplatform.closing.application.ClosingRecommendationScorer.ScoreResult;
import com.sunmo.stockplatform.closing.application.OvernightExecutionSimulator.ExecutionResult;
import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.closing.domain.OvernightPerformance;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import com.sunmo.stockplatform.scanner.infrastructure.ScannerDetectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 과거 날짜를 하루씩 돌며 "그날 15:15 기준으로 뽑았을 후보"를 다시 고르고 다음날 결과를 채점하는 클래스예요.
 * 지난 경기 녹화본을 돌려 보며 "이 작전을 썼다면 몇 점이었을까"를 계산해 보는 코치와 같아요. 단, 그때 손에 없던 정보는 쓰지 않도록 시간 기준을 지켜요.
 */
@Service
@Transactional(readOnly = true)
public class OvernightBacktestService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private static final String TIMEFRAME = "5M";

    private final ScannerDetectionRepository detections;
    private final StockCandleRepository candles;
    private final ClosingRecommendationScorer scorer;
    private final BacktestIntegrityService integrity;
    private final IntradayMovingAverageService intradayMa;
    private final DailyMovingAverageService dailyMa;
    private final ClosingPrecisionEvaluator precisionEvaluator;
    private final ClosingRecommendationProperties properties;
    private final ClosingTradingCalendar calendar;
    private final OvernightExecutionSimulator execution;

    /**
     * 하는 일: 의존 객체를 받아 서비스를 만들어요.
     * 받는 것: 탐지 저장소, 봉 저장소, 점수 계산기, 백테스트 무결성 검사기, 5분봉·일봉 이동평균 서비스, 정밀 후보 평가기, 추천 설정, 거래일 달력, 가상 체결 계산기.
     * 돌려주는 것: 없음(생성자).
     */
    public OvernightBacktestService(ScannerDetectionRepository detections, StockCandleRepository candles,
            ClosingRecommendationScorer scorer, BacktestIntegrityService integrity,
            IntradayMovingAverageService intradayMa, DailyMovingAverageService dailyMa,
            ClosingPrecisionEvaluator precisionEvaluator, ClosingRecommendationProperties properties,
            ClosingTradingCalendar calendar, OvernightExecutionSimulator execution) {
        this.detections = detections;
        this.candles = candles;
        this.scorer = scorer;
        this.integrity = integrity;
        this.intradayMa = intradayMa;
        this.dailyMa = dailyMa;
        this.precisionEvaluator = precisionEvaluator;
        this.properties = properties;
        this.calendar = calendar;
        this.execution = execution;
    }

    /**
     * 하는 일: 기간 안의 날짜마다 정밀 후보를 다시 고르고 다음날 성과·청산 전략별 성과·알고리즘별 비교를 계산해요.
     * 받는 것: 시작일(없으면 끝일-20일), 끝일(없으면 달력상 어제), 하루 최대 후보 수(1~30), 최소 기회 점수(기본 35), 최대 위험 점수(기본 65), 목표·손절 수익률(기본 +3%/-2%).
     * 돌려주는 것: 기간 요약(승률, 평균 수익률 등), 무결성 검사, 청산 전략 요약, 알고리즘 비교, 종목별 행을 담은 응답.
     */
    public OvernightBacktestResponse run(LocalDate from, LocalDate to, int limit, BigDecimal minOpportunity,
            BigDecimal maxRisk, BigDecimal targetRate, BigDecimal stopRate) {
        // 준비: 기본값을 채우고, 기간 전체의 탐지를 한 번에 읽어 세션 날짜별로 묶어요.
        LocalDate end = to == null ? LocalDate.now(MARKET_ZONE).minusDays(1) : to;
        LocalDate start = from == null ? end.minusDays(20) : from;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from must be before or equal to to");
        }
        int safeLimit = Math.min(Math.max(limit, 1), 30);
        BigDecimal minOpportunityValue = minOpportunity == null ? bd("35") : minOpportunity;
        BigDecimal maxRiskValue = maxRisk == null ? bd("65") : maxRisk;
        BigDecimal target = targetRate == null ? bd("3") : targetRate;
        BigDecimal stop = stopRate == null ? bd("-2") : stopRate;
        Instant queryFrom = start.atStartOfDay(MARKET_ZONE).toInstant();
        Instant queryTo = end.plusDays(1).atStartOfDay(MARKET_ZONE).toInstant();
        Map<LocalDate, List<ScannerDetection>> byDate = groupByDate(detections
                .findByDetectedAtBetweenOrderByDetectedAtAsc(queryFrom, queryTo));

        List<BacktestEvaluation> evaluations = new ArrayList<>();
        int tradingDays = 0;
        // 처리: 날짜마다 평가 시작(기본 14:30)~입력 마감(기본 15:15) 사이 탐지만 남기고 정밀 후보 평가기로 순위를 매겨요.
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            Instant cutoffAt = date.atTime(properties.evaluationStart()).atZone(MARKET_ZONE).toInstant();
            Instant asOf = date.atTime(properties.featureFreezeAt()).atZone(MARKET_ZONE).toInstant();
            List<ScannerDetection> source = byDate.getOrDefault(date, List.of()).stream()
                    .filter(detection -> !detection.getDetectedAt().isBefore(cutoffAt)
                            && !detection.getDetectedAt().isAfter(asOf))
                    .sorted(Comparator.comparing(ScannerDetection::getDetectedAt).reversed())
                    .toList();
            if (source.isEmpty())
                continue;
            // 탐지가 하나라도 있었던 날만 거래일로 세요.
            tradingDays++;
            List<ScoredDetection> candidates = precisionEvaluator.ranked(source, cutoffAt, asOf,
                    minOpportunityValue, maxRiskValue, safeLimit).stream()
                    .map(row -> new ScoredDetection(row.detection(), row.score()))
                    .toList();
            for (int index = 0; index < candidates.size(); index++) {
                evaluations.add(evaluate(date, index + 1, candidates.get(index), target, stop));
            }
        }

        // 결과: 완료(COMPLETED)된 행만으로 승률·평균 수익률을 계산해요.
        List<OvernightBacktestRow> rows = evaluations.stream().map(BacktestEvaluation::row).toList();
        List<OvernightBacktestRow> completed = rows.stream().filter(row -> "COMPLETED".equals(row.status())).toList();
        int missing = rows.size() - completed.size();
        return new OvernightBacktestResponse(
                start,
                end,
                tradingDays,
                rows.size(),
                completed.size(),
                missing,
                win(completed, OvernightBacktestRow::openReturnRate),
                win(completed, OvernightBacktestRow::closeReturnRate),
                avg(completed, OvernightBacktestRow::openReturnRate),
                avg(completed, OvernightBacktestRow::closeReturnRate),
                avg(completed, OvernightBacktestRow::maxReturnRate),
                avg(completed, OvernightBacktestRow::maxDrawdownRate),
                target,
                stop,
                OvernightPerformance.VERSION,
                integrity.validate(evaluations, target, stop),
                strategySummaries(evaluations, target, stop),
                algorithmSummaries(start, end, safeLimit, minOpportunityValue, maxRiskValue, target, stop, byDate),
                rows);
    }

    /**
     * 하는 일: 네 가지 추천 알고리즘을 같은 기간·같은 청산 조건으로 계산해 비교하고, 그중 하나에 "추천" 표시를 붙여요.
     * 받는 것: 기간, 하루 최대 후보 수, 최소 기회·최대 위험 점수, 목표·손절 수익률, 날짜별 탐지.
     * 돌려주는 것: 알고리즘별 요약 목록.
     */
    private List<RecommendationAlgorithmSummary> algorithmSummaries(LocalDate start, LocalDate end, int limit,
            BigDecimal minOpportunity, BigDecimal maxRisk, BigDecimal targetRate, BigDecimal stopRate,
            Map<LocalDate, List<ScannerDetection>> byDate) {
        List<AlgorithmProfile> profiles = List.of(
                new AlgorithmProfile("SCANNER_BASELINE", "기존 탐지 점수", minOpportunity, maxRisk),
                new AlgorithmProfile("CLOSING_NO_MA_THRESHOLD_55", "이평선 점수·게이트 제거(기준점수 유지)", minOpportunity, maxRisk),
                new AlgorithmProfile("CLOSING_MA", "마감 점수 + 이평선", minOpportunity, maxRisk),
                new AlgorithmProfile("CLOSING_MA_STRICT", "보수형 이평선", minOpportunity.add(bd("10")),
                        maxRisk.subtract(bd("10")).max(BigDecimal.ZERO)));
        List<RecommendationAlgorithmSummary> summaries = profiles.stream()
                .map(profile -> summarizeAlgorithm(profile,
                        evaluationsForProfile(start, end, limit, profile, targetRate, stopRate, byDate)))
                .toList();
        // "추천" 표시는 신뢰도(완료 표본 수) → 평균 종가 수익률 → 종가 승률 순으로 가장 높은 알고리즘에 붙여요.
        // 같은 표본 안에서 고른 것이라 운영 기본값 근거로 쓰려면 별도 비교 검증이 필요해요.
        RecommendationAlgorithmSummary best = summaries.stream()
                .filter(summary -> summary.completed() > 0)
                .max(Comparator.comparing((RecommendationAlgorithmSummary summary) -> confidenceRank(summary.confidence()))
                        .thenComparing(summary -> value(summary.averageCloseReturn()))
                        .thenComparing(summary -> value(summary.winRateClose())))
                .orElse(null);
        if (best == null)
            return summaries;
        return summaries.stream()
                .map(summary -> withRecommended(summary, summary.algorithm().equals(best.algorithm())))
                .toList();
    }

    /**
     * 하는 일: 한 알고리즘 설정으로 기간 안의 날짜마다 후보를 고르고 다음날 성과를 계산해요.
     * 받는 것: 기간, 하루 최대 후보 수, 알고리즘 설정, 목표·손절 수익률, 날짜별 탐지.
     * 돌려주는 것: 종목별 평가 목록.
     */
    private List<BacktestEvaluation> evaluationsForProfile(LocalDate start, LocalDate end, int limit,
            AlgorithmProfile profile, BigDecimal targetRate, BigDecimal stopRate,
            Map<LocalDate, List<ScannerDetection>> byDate) {
        List<BacktestEvaluation> values = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            Instant cutoffAt = date.atTime(properties.evaluationStart()).atZone(MARKET_ZONE).toInstant();
            Instant asOf = date.atTime(properties.featureFreezeAt()).atZone(MARKET_ZONE).toInstant();
            // "이평선 제거" 알고리즘은 이동평균 없이 대표 탐지를 고르고, 나머지는 이동평균을 포함해 골라요.
            List<ClosingPrecisionEvaluator.Assessment> assessments = "CLOSING_NO_MA_THRESHOLD_55".equals(profile.code())
                    ? precisionEvaluator.representativesWithoutMovingAverage(byDate.getOrDefault(date, List.of()),
                            cutoffAt, asOf, profile.minOpportunity(), profile.maxRisk())
                    : precisionEvaluator.representatives(byDate.getOrDefault(date, List.of()), cutoffAt, asOf,
                            profile.minOpportunity(), profile.maxRisk());
            List<ScoredDetection> candidates = assessments.stream()
                    // 자격을 통과한(QUALIFIED) 후보만 남기고, 보수형은 일봉이 60개 이상인 종목만 써요. 점수 높은 순, 같으면 늦게 탐지된 순으로 정렬해요.
                    .filter(row -> row.reason().equals("QUALIFIED"))
                    .filter(row -> !"CLOSING_MA_STRICT".equals(profile.code())
                            || ((Number) row.dataReadiness().getOrDefault("dailyCandles", 0)).intValue() >= 60)
                    .map(row -> new ScoredDetection(row.detection(), scoreFor(profile, row.detection())))
                    .sorted(Comparator.comparing((ScoredDetection item) -> item.score().score()).reversed()
                            .thenComparing(item -> item.detection().getDetectedAt(), Comparator.reverseOrder()))
                    .limit(Math.min(limit, ClosingPrecisionEvaluator.LIMITED_MODE_MAX_CANDIDATES))
                    .toList();
            for (int index = 0; index < candidates.size(); index++) {
                values.add(evaluate(date, index + 1, candidates.get(index), targetRate, stopRate));
            }
        }
        return values;
    }

    /**
     * 하는 일: 알고리즘 종류에 맞춰 탐지의 점수를 계산해요.
     * 받는 것: 알고리즘 설정, 탐지. 돌려주는 것: 점수 결과.
     */
    private ScoreResult scoreFor(AlgorithmProfile profile, ScannerDetection detection) {
        // 기존 탐지 점수: 탐지 점수 + 기회 점수 - 위험 점수×0.35를 0~100으로 자른 값이에요.
        if ("SCANNER_BASELINE".equals(profile.code())) {
            BigDecimal score = value(detection.getScore()).add(value(detection.getOpportunityScore()))
                    .subtract(value(detection.getRiskScore()).multiply(bd("0.35")))
                    .max(BigDecimal.ZERO)
                    .min(bd("100"))
                    .setScale(3, RoundingMode.HALF_UP);
            return new ScoreResult(score, "{}", "{}");
        }
        if ("CLOSING_NO_MA_THRESHOLD_55".equals(profile.code())) {
            return scorer.score(detection);
        }
        // 이평선 포함 알고리즘: 탐지 시각 기준 5분봉·일봉 이동평균을 함께 넣어 점수를 계산해요.
        return scorer.score(detection, intradayMa.calculate(detection), dailyMa.calculate(detection));
    }

    /**
     * 하는 일: 한 알고리즘의 평가 목록을 승률, 평균 수익률, 목표·손절 도달률, 손익비, 종목 다양성, 날짜 쏠림 같은 요약으로 만들어요.
     * 받는 것: 알고리즘 설정, 평가 목록. 돌려주는 것: 알고리즘 요약(추천 표시는 false).
     */
    private RecommendationAlgorithmSummary summarizeAlgorithm(AlgorithmProfile profile,
            List<BacktestEvaluation> evaluations) {
        List<OvernightBacktestRow> rows = evaluations.stream().map(BacktestEvaluation::row).toList();
        List<OvernightBacktestRow> completed = rows.stream().filter(row -> "COMPLETED".equals(row.status())).toList();
        Set<String> stocks = new HashSet<>();
        Map<LocalDate, Integer> byDate = new HashMap<>();
        for (OvernightBacktestRow row : rows) {
            stocks.add(row.stockCode());
            byDate.merge(row.recommendationDate(), 1, Integer::sum);
        }
        return new RecommendationAlgorithmSummary(
                profile.code(),
                profile.label(),
                rows.size(),
                completed.size(),
                rows.size() - completed.size(),
                confidence(completed.size()),
                win(completed, OvernightBacktestRow::openReturnRate),
                win(completed, OvernightBacktestRow::closeReturnRate),
                avg(completed, OvernightBacktestRow::openReturnRate),
                avg(completed, OvernightBacktestRow::closeReturnRate),
                avg(completed, OvernightBacktestRow::maxReturnRate),
                avg(completed, OvernightBacktestRow::maxDrawdownRate),
                rate(completed, OvernightBacktestRow::targetHit),
                rate(completed, OvernightBacktestRow::stopHit),
                profitFactor(completed),
                stocks.size(),
                maxDateConcentration(rows, byDate),
                false);
    }

    /**
     * 하는 일: 요약을 그대로 복사하면서 "추천" 표시만 바꿔요.
     * 받는 것: 요약, 추천 여부. 돌려주는 것: 새 요약.
     */
    private RecommendationAlgorithmSummary withRecommended(RecommendationAlgorithmSummary summary,
            boolean recommended) {
        return new RecommendationAlgorithmSummary(
                summary.algorithm(),
                summary.label(),
                summary.sampleSize(),
                summary.completed(),
                summary.dataMissing(),
                summary.confidence(),
                summary.winRateOpen(),
                summary.winRateClose(),
                summary.averageOpenReturn(),
                summary.averageCloseReturn(),
                summary.averageMaxReturn(),
                summary.averageMaxDrawdown(),
                summary.targetHitRate(),
                summary.stopHitRate(),
                summary.profitFactor(),
                summary.uniqueStocks(),
                summary.maxDateConcentrationRate(),
                recommended);
    }

    /**
     * 하는 일: 탐지를 세션 날짜별로 묶어요(읽은 순서 유지).
     * 받는 것: 탐지 목록. 돌려주는 것: 날짜 → 탐지 목록 Map.
     */
    private Map<LocalDate, List<ScannerDetection>> groupByDate(List<ScannerDetection> rows) {
        Map<LocalDate, List<ScannerDetection>> byDate = new LinkedHashMap<>();
        for (ScannerDetection row : rows) {
            byDate.computeIfAbsent(row.getSessionDate(), ignored -> new ArrayList<>()).add(row);
        }
        return byDate;
    }

    /**
     * 하는 일: 후보 한 건의 진입 봉과 다음날 봉을 찾아 시가·종가·최고·최저 수익률과 목표·손절 도달 여부를 계산해요.
     * 받는 것: 추천일, 순위, 점수가 붙은 탐지, 목표·손절 수익률(%).
     * 돌려주는 것: 평가 결과(행 + 다음날 봉). 진입 봉이나 다음날 봉이 없으면 상태만 표시한 행이고,
     * 다음날 봉이 NextSessionCoverage 기준으로 빠짐없이 모이지 않았으면 NEXT_SESSION_DATA_INCOMPLETE 행(완료로 세지 않아요)이에요.
     */
    private BacktestEvaluation evaluate(LocalDate date, int rank, ScoredDetection scored, BigDecimal targetRate,
            BigDecimal stopRate) {
        ScannerDetection detection = scored.detection();
        StockCandle entry = entryCandle(detection, date);
        if (entry == null) {
            OvernightBacktestRow row = new OvernightBacktestRow(date, rank, detection.getStock().getStockCode(),
                    detection.getStock().getStockName(), detection.getStock().getMarket().name(), detection.getType().name(),
                    detection.getDetectedAt(), detection.getDetectedPrice(), null, null, scored.score().score(),
                    detection.getOpportunityScore(), detection.getRiskScore(), null, null, null, null, null,
                    false, false, "ENTRY_DATA_MISSING");
            return new BacktestEvaluation(row, List.of());
        }
        List<StockCandle> nextSession = nextSessionCandles(detection, date);
        if (nextSession.isEmpty()) {
            OvernightBacktestRow row = new OvernightBacktestRow(date, rank, detection.getStock().getStockCode(),
                    detection.getStock().getStockName(), detection.getStock().getMarket().name(), detection.getType().name(),
                    detection.getDetectedAt(), detection.getDetectedPrice(), entry.getStartTime(), entry.getOpen(),
                    scored.score().score(),
                    detection.getOpportunityScore(), detection.getRiskScore(), null, null, null, null, null, false, false,
                    "NEXT_SESSION_DATA_MISSING");
            return new BacktestEvaluation(row, List.of());
        }
        // 다음날 성과 추적(OvernightPerformanceService)과 같은 완전성 규칙을 써요: 09:00~15:15 정규 봉이 하나씩 모두 있고
        // 15:25 종가 봉이 거래량과 함께 있어야 해요. 세션 전체를 요구하도록 판정 시각은 그 세션의 정산 시각(마감+2분)으로 둬요.
        LocalDate nextDate = calendar.nextTradingDay(date);
        Instant nextClose = calendar.close(nextDate);
        if (!NextSessionCoverage.missing(calendar.open(nextDate), nextClose, NextSessionCoverage.settledAt(nextClose),
                nextSession).isEmpty()) {
            OvernightBacktestRow row = new OvernightBacktestRow(date, rank, detection.getStock().getStockCode(),
                    detection.getStock().getStockName(), detection.getStock().getMarket().name(), detection.getType().name(),
                    detection.getDetectedAt(), detection.getDetectedPrice(), entry.getStartTime(), entry.getOpen(),
                    scored.score().score(),
                    detection.getOpportunityScore(), detection.getRiskScore(), nextDate, null, null, null, null, false, false,
                    "NEXT_SESSION_DATA_INCOMPLETE");
            return new BacktestEvaluation(row, List.of());
        }
        // 진입 기준가는 진입 봉의 시가예요. 다음날 고가·저가로 최대 수익률과 최대 하락률을 구해요.
        BigDecimal base = entry.getOpen();
        BigDecimal high = nextSession.stream().map(StockCandle::getHigh).reduce(BigDecimal::max).orElse(null);
        BigDecimal low = nextSession.stream().map(StockCandle::getLow).reduce(BigDecimal::min).orElse(null);
        BigDecimal maxReturn = pct(high, base);
        BigDecimal maxDrawdown = pct(low, base);
        OvernightBacktestRow row = new OvernightBacktestRow(date, rank, detection.getStock().getStockCode(),
                detection.getStock().getStockName(), detection.getStock().getMarket().name(), detection.getType().name(),
                detection.getDetectedAt(), detection.getDetectedPrice(), entry.getStartTime(), base,
                scored.score().score(), detection.getOpportunityScore(),
                detection.getRiskScore(), nextSession.getFirst().getStartTime().atZone(MARKET_ZONE).toLocalDate(),
                pct(nextSession.getFirst().getOpen(), base), pct(nextSession.getLast().getClose(), base), maxReturn,
                maxDrawdown, maxReturn != null && maxReturn.compareTo(targetRate) >= 0,
                maxDrawdown != null && maxDrawdown.compareTo(stopRate) <= 0, "COMPLETED");
        return new BacktestEvaluation(row, nextSession);
    }

    /**
     * 하는 일: 입력 마감(featureFreezeAt)+5분에 시작하는 확정 5분봉을 진입 봉으로 찾아요(진입 마감 전까지).
     * 받는 것: 탐지, 추천일. 돌려주는 것: 진입 봉, 없으면 null.
     */
    private StockCandle entryCandle(ScannerDetection detection, LocalDate recommendationDate) {
        // 15:15 strategy requires a captured post-completion tick; five-minute OHLC cannot reconstruct it.
        if (!properties.featureFreezeAt().isBefore(LocalTime.of(15, 15))) return null;
        // 입력 마감이 15:15보다 이를 때만 이 경로를 써요. 예: 14:50 마감이면 14:55 봉 시가가 진입가예요.
        Instant expected = recommendationDate.atTime(properties.featureFreezeAt()).atZone(MARKET_ZONE).toInstant()
                .plus(Duration.ofMinutes(5));
        Instant deadline = recommendationDate.atTime(properties.entryDeadline()).atZone(MARKET_ZONE).toInstant();
        return candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        detection.getStock().getId(), TIMEFRAME, expected, deadline)
                .stream().filter(StockCandle::isFinalCandle)
                .filter(candle -> candle.getStartTime().equals(expected))
                .filter(candle -> candle.getOpen() != null && candle.getOpen().signum() > 0)
                .findFirst().orElse(null);
    }

    /**
     * 하는 일: 추천일 다음 거래일의 확정 5분봉을 성과 추적 서비스와 같은 기준으로 걸러 가져와요.
     * 장 시작~마감 안에서 5분 경계에 맞고, 가격 정합(저가>0, 고가>=시가·종가, 저가<=시가·종가)인 봉만 시간순으로 남겨요.
     * 완전성(빠진 봉) 판정은 evaluate에서 NextSessionCoverage로 해요.
     * 받는 것: 탐지, 추천일. 돌려주는 것: 다음 거래일 5분봉 목록(없으면 빈 목록).
     */
    private List<StockCandle> nextSessionCandles(ScannerDetection detection, LocalDate recommendationDate) {
        LocalDate nextDate = calendar.nextTradingDay(recommendationDate);
        Instant from = calendar.open(nextDate);
        Instant close = calendar.close(nextDate);
        return candles
                .findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        detection.getStock().getId(), TIMEFRAME, from, close.plusSeconds(1))
                .stream()
                .filter(StockCandle::isFinalCandle)
                .filter(candle -> !candle.getStartTime().isBefore(from) && !candle.getStartTime().isAfter(close))
                .filter(candle -> Duration.between(from, candle.getStartTime()).getSeconds() % 300 == 0)
                .filter(this::validPrices)
                .sorted(Comparator.comparing(StockCandle::getStartTime))
                .toList();
    }

    /**
     * 하는 일: 봉의 시가·고가·저가·종가가 모두 있고 서로 앞뒤가 맞는지 확인해요(성과 추적 서비스와 같은 규칙).
     * 받는 것: 5분봉. 돌려주는 것: 정상이면 true.
     */
    private boolean validPrices(StockCandle candle) {
        return candle.getOpen() != null && candle.getHigh() != null && candle.getLow() != null
                && candle.getClose() != null && candle.getLow().signum() > 0
                && candle.getHigh().compareTo(candle.getOpen().max(candle.getClose())) >= 0
                && candle.getLow().compareTo(candle.getOpen().min(candle.getClose())) <= 0;
    }

    /**
     * 하는 일: 여섯 가지 청산 방식(다음날 시가, 다음날 종가, 익절/손절 우선, 목표 후 VWAP 이탈, 목표 후 20봉선 이탈, 추세 양호 시 보유 연장)의 요약을 만들어요.
     * 받는 것: 평가 목록, 목표·손절 수익률(%). 돌려주는 것: 청산 방식별 요약 목록.
     */
    private List<OvernightExitStrategySummary> strategySummaries(List<BacktestEvaluation> evaluations,
            BigDecimal targetRate, BigDecimal stopRate) {
        return List.of(
                summarize("NEXT_OPEN", "다음날 시가 매도", evaluations, evaluation -> nextOpen(evaluation)),
                summarize("NEXT_CLOSE", "다음날 종가 매도", evaluations, evaluation -> nextClose(evaluation)),
                summarize("TARGET_OR_STOP", "목표/손절 우선 매도", evaluations,
                        evaluation -> targetOrStop(evaluation, targetRate, stopRate)),
                summarize("VWAP_TRAILING", "목표 후 VWAP 이탈 매도", evaluations,
                        evaluation -> vwapTrailing(evaluation, targetRate, stopRate)),
                summarize("MA20_TRAILING", "목표 후 20봉선 이탈 매도", evaluations,
                        evaluation -> ma20Trailing(evaluation, targetRate, stopRate)),
                summarize("EXTEND_WHILE_HEALTHY", "추세 양호 시 보유 연장", evaluations,
                        evaluation -> extendWhileHealthy(evaluation, targetRate, stopRate)));
    }

    /**
     * 하는 일: 한 청산 방식을 모든 평가에 적용해 승률, 평균 비용 전·후 수익률, 목표·손절 도달률을 계산해요.
     * 받는 것: 방식 코드, 이름, 평가 목록, 방식별 계산 함수. 돌려주는 것: 청산 방식 요약.
     */
    private OvernightExitStrategySummary summarize(String strategy, String label, List<BacktestEvaluation> evaluations,
            Function<BacktestEvaluation, StrategyResult> simulator) {
        List<StrategyResult> values = evaluations.stream()
                .map(simulator)
                .filter(Objects::nonNull)
                .filter(result -> result.returnRate() != null)
                .toList();
        if (values.isEmpty()) {
            return new OvernightExitStrategySummary(strategy, label, 0, null, null, null, null, null, null, 0,
                    false, OvernightExecutionSimulator.VERSION);
        }
        long wins = values.stream().filter(result -> result.returnRate().signum() > 0).count();
        long targetHits = values.stream().filter(StrategyResult::targetHit).count();
        long stopHits = values.stream().filter(StrategyResult::stopHit).count();
        int ambiguous = (int) values.stream().filter(StrategyResult::ambiguous).count();
        // 모든 결과에 비용이 적용됐을 때만 비용 후 평균 수익률을 채워요. 평균 최대 하락률은 방식과 관계없이 완료 행 전체 값이에요.
        boolean costsApplied = values.stream().allMatch(StrategyResult::costsApplied);
        return new OvernightExitStrategySummary(
                strategy,
                label,
                values.size(),
                ratio(wins, values.size()),
                values.stream().map(StrategyResult::returnRate).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP),
                costsApplied ? values.stream().map(StrategyResult::netReturnRate).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP) : null,
                avg(evaluations.stream()
                        .map(BacktestEvaluation::row)
                        .filter(row -> "COMPLETED".equals(row.status()))
                        .toList(), OvernightBacktestRow::maxDrawdownRate),
                ratio(targetHits, values.size()),
                ratio(stopHits, values.size()),
                ambiguous,
                costsApplied,
                OvernightExecutionSimulator.VERSION);
    }

    /**
     * 하는 일: 다음날 첫 봉 시가에 판다고 보고 가상 체결 결과를 만들어요(NEXT_OPEN).
     * 받는 것: 평가 한 건. 돌려주는 것: 결과, 완료가 아니면 null.
     */
    private StrategyResult nextOpen(BacktestEvaluation evaluation) {
        if (!completed(evaluation))
            return null;
        StockCandle first = evaluation.candles().getFirst();
        return result(execution.exit(evaluation.row().buyReferencePrice(), first.getStartTime(), first.getOpen(),
                "NEXT_OPEN", evaluation.row().targetHit(), evaluation.row().stopHit(), false));
    }

    /**
     * 하는 일: 다음날 마지막 봉 종가에 판다고 보고 가상 체결 결과를 만들어요(NEXT_CLOSE).
     * 받는 것: 평가 한 건. 돌려주는 것: 결과, 완료가 아니면 null.
     */
    private StrategyResult nextClose(BacktestEvaluation evaluation) {
        if (!completed(evaluation))
            return null;
        StockCandle last = evaluation.candles().getLast();
        return result(execution.exit(evaluation.row().buyReferencePrice(), last.getStartTime(), last.getClose(),
                "NEXT_CLOSE", evaluation.row().targetHit(), evaluation.row().stopHit(), false));
    }

    /**
     * 하는 일: 익절/손절 선에 먼저 닿는 쪽으로 파는 방식을 가상 체결 계산기에 맡겨요.
     * 받는 것: 평가 한 건, 목표·손절 수익률(%). 돌려주는 것: 결과, 완료가 아니면 null.
     */
    private StrategyResult targetOrStop(BacktestEvaluation evaluation, BigDecimal targetRate, BigDecimal stopRate) {
        if (!completed(evaluation))
            return null;
        return result(execution.targetOrStop(evaluation.row().buyReferencePrice(), evaluation.candles(),
                targetRate, stopRate));
    }

    /**
     * 하는 일: 목표 도달 전에는 손절만 보고, 목표에 닿은 뒤에는 종가가 VWAP(거래량 가중 평균가) 아래로 내려오면 파는 방식이에요.
     * 받는 것: 평가 한 건, 목표·손절 수익률(%). 돌려주는 것: 결과, 완료가 아니면 null.
     */
    private StrategyResult vwapTrailing(BacktestEvaluation evaluation, BigDecimal targetRate, BigDecimal stopRate) {
        if (!completed(evaluation))
            return null;
        BigDecimal base = evaluation.row().buyReferencePrice();
        BigDecimal targetPrice = threshold(base, targetRate);
        BigDecimal stopPrice = threshold(base, stopRate);
        boolean targetReached = false;
        boolean ambiguous = false;
        long cumulativeVolume = 0;
        BigDecimal cumulativeValue = BigDecimal.ZERO;
        // 봉마다 누적 거래대금/누적 거래량으로 VWAP를 갱신해요. 목표에 닿은 뒤에는 손절선을 더 보지 않아요.
        for (StockCandle candle : evaluation.candles()) {
            cumulativeVolume += Math.max(candle.getVolume(), 0);
            cumulativeValue = cumulativeValue.add(value(candle.getTradingValue()));
            boolean targetHit = candle.getHigh().compareTo(targetPrice) >= 0;
            boolean stopHit = candle.getLow().compareTo(stopPrice) <= 0;
            if (!targetReached && targetHit && stopHit) {
                return result(execution.exit(base, candle.getStartTime(), stopPrice,
                        "AMBIGUOUS_STOP", true, true, true));
            }
            if (!targetReached && stopHit) {
                return result(execution.exit(base, candle.getStartTime(), stopPrice,
                        "STOP", false, true, false));
            }
            if (targetHit) {
                targetReached = true;
            }
            BigDecimal vwap = cumulativeVolume == 0 ? null
                    : cumulativeValue.divide(BigDecimal.valueOf(cumulativeVolume), 6, RoundingMode.HALF_UP);
            if (targetReached && vwap != null && candle.getClose().compareTo(vwap) < 0) {
                return result(execution.exit(base, candle.getStartTime(), candle.getClose(),
                        "VWAP_TRAILING", true, false, ambiguous));
            }
        }
        // 끝까지 이탈이 없으면 마지막 봉 종가로 시간 청산해요.
        StockCandle last = evaluation.candles().getLast();
        return result(execution.exit(base, last.getStartTime(), last.getClose(),
                "TIME_EXIT", targetReached, false, ambiguous));
    }

    /**
     * 하는 일: 목표 도달 전에는 손절만 보고, 목표에 닿은 뒤에는 종가가 최근 20개 5분봉 종가 평균(20봉선) 아래면 파는 방식이에요.
     * 받는 것: 평가 한 건, 목표·손절 수익률(%). 돌려주는 것: 결과, 완료가 아니면 null.
     */
    private StrategyResult ma20Trailing(BacktestEvaluation evaluation, BigDecimal targetRate, BigDecimal stopRate) {
        if (!completed(evaluation))
            return null;
        BigDecimal base = evaluation.row().buyReferencePrice();
        BigDecimal targetPrice = threshold(base, targetRate);
        BigDecimal stopPrice = threshold(base, stopRate);
        boolean targetReached = false;
        List<BigDecimal> closes = new ArrayList<>();
        for (StockCandle candle : evaluation.candles()) {
            closes.add(candle.getClose());
            boolean targetHit = candle.getHigh().compareTo(targetPrice) >= 0;
            boolean stopHit = candle.getLow().compareTo(stopPrice) <= 0;
            if (!targetReached && targetHit && stopHit) {
                return result(execution.exit(base, candle.getStartTime(), stopPrice,
                        "AMBIGUOUS_STOP", true, true, true));
            }
            if (!targetReached && stopHit) {
                return result(execution.exit(base, candle.getStartTime(), stopPrice,
                        "STOP", false, true, false));
            }
            if (targetHit) {
                targetReached = true;
            }
            if (targetReached && closes.size() >= 20 && candle.getClose().compareTo(ma(closes, 20)) < 0) {
                return result(execution.exit(base, candle.getStartTime(), candle.getClose(),
                        "MA20_TRAILING", true, false, false));
            }
        }
        StockCandle last = evaluation.candles().getLast();
        return result(execution.exit(base, last.getStartTime(), last.getClose(),
                "TIME_EXIT", targetReached, false, false));
    }

    /**
     * 하는 일: 목표에 닿은 뒤 VWAP 이탈, 20봉선 이탈, 당일 고가 대비 -1.5% 하락 중 하나라도 생기면 파는 방식이에요(HEALTH_BREAK).
     * 받는 것: 평가 한 건, 목표·손절 수익률(%). 돌려주는 것: 결과, 완료가 아니면 null.
     */
    private StrategyResult extendWhileHealthy(BacktestEvaluation evaluation, BigDecimal targetRate, BigDecimal stopRate) {
        if (!completed(evaluation))
            return null;
        BigDecimal base = evaluation.row().buyReferencePrice();
        BigDecimal targetPrice = threshold(base, targetRate);
        BigDecimal stopPrice = threshold(base, stopRate);
        boolean targetReached = false;
        long cumulativeVolume = 0;
        BigDecimal cumulativeValue = BigDecimal.ZERO;
        BigDecimal sessionHigh = BigDecimal.ZERO;
        List<BigDecimal> closes = new ArrayList<>();
        for (StockCandle candle : evaluation.candles()) {
            closes.add(candle.getClose());
            cumulativeVolume += Math.max(candle.getVolume(), 0);
            cumulativeValue = cumulativeValue.add(value(candle.getTradingValue()));
            sessionHigh = sessionHigh.max(candle.getHigh());
            boolean targetHit = candle.getHigh().compareTo(targetPrice) >= 0;
            boolean stopHit = candle.getLow().compareTo(stopPrice) <= 0;
            if (!targetReached && targetHit && stopHit) {
                return result(execution.exit(base, candle.getStartTime(), stopPrice,
                        "AMBIGUOUS_STOP", true, true, true));
            }
            if (!targetReached && stopHit) {
                return result(execution.exit(base, candle.getStartTime(), stopPrice,
                        "STOP", false, true, false));
            }
            if (targetHit) {
                targetReached = true;
            }
            if (!targetReached)
                continue;
            BigDecimal vwap = cumulativeVolume == 0 ? null
                    : cumulativeValue.divide(BigDecimal.valueOf(cumulativeVolume), 6, RoundingMode.HALF_UP);
            boolean vwapBroken = vwap != null && candle.getClose().compareTo(vwap) < 0;
            boolean ma20Broken = closes.size() >= 20 && candle.getClose().compareTo(ma(closes, 20)) < 0;
            boolean highPulledBack = pct(candle.getClose(), sessionHigh).compareTo(bd("-1.5")) <= 0;
            if (vwapBroken || ma20Broken || highPulledBack) {
                return result(execution.exit(base, candle.getStartTime(), candle.getClose(),
                        "HEALTH_BREAK", true, false, false));
            }
        }
        StockCandle last = evaluation.candles().getLast();
        return result(execution.exit(base, last.getStartTime(), last.getClose(),
                "TIME_EXIT", targetReached, false, false));
    }

    /**
     * 하는 일: 평가가 완료 상태이고 다음날 봉이 있는지 확인해요.
     * 받는 것: 평가 한 건. 돌려주는 것: 계산 가능하면 true.
     */
    private boolean completed(BacktestEvaluation evaluation) {
        return "COMPLETED".equals(evaluation.row().status()) && !evaluation.candles().isEmpty();
    }

    /**
     * 하는 일: 가상 체결 결과를 청산 방식 요약용 작은 결과로 옮겨요.
     * 받는 것: 체결 결과(없을 수 있음). 돌려주는 것: StrategyResult 또는 null.
     */
    private StrategyResult result(ExecutionResult value) {
        return value == null ? null : new StrategyResult(value.grossReturnRate(), value.netReturnRate(),
                value.targetHit(), value.stopHit(), value.ambiguous(), value.costsApplied());
    }

    /**
     * 하는 일: 기준가에 퍼센트 비율을 적용한 가격을 구해요.
     * 받는 것: 기준가, 비율(%). 돌려주는 것: 적용된 가격.
     */
    private BigDecimal threshold(BigDecimal base, BigDecimal rate) {
        return base.multiply(BigDecimal.ONE.add(rate.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)));
    }

    /**
     * 하는 일: 목록 마지막 period개 값의 평균을 구해요.
     * 받는 것: 값 목록, 개수. 돌려주는 것: 소수 6자리 평균.
     */
    private BigDecimal ma(List<BigDecimal> values, int period) {
        return values.subList(values.size() - period, values.size()).stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);
    }

    /**
     * 하는 일: 개수를 전체 대비 백분율로 바꿔요.
     * 받는 것: 개수, 전체 수. 돌려주는 것: 백분율, 전체가 0이면 null.
     */
    private BigDecimal ratio(long count, int total) {
        if (total == 0)
            return null;
        return BigDecimal.valueOf(count).multiply(bd("100"))
                .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
    }

    /**
     * 하는 일: 조건을 만족하는 행의 비율(%)을 구해요.
     * 받는 것: 행 목록, 조건. 돌려주는 것: 백분율, 행이 없으면 null.
     */
    private BigDecimal rate(List<OvernightBacktestRow> rows, Predicate<OvernightBacktestRow> predicate) {
        if (rows.isEmpty())
            return null;
        return ratio(rows.stream().filter(predicate).count(), rows.size());
    }

    /**
     * 하는 일: 손익비(종가 수익의 합 ÷ 종가 손실의 합)를 구해요.
     * 받는 것: 완료 행 목록. 돌려주는 것: 손익비. 손실이 없으면 999.999999, 수익도 없으면 null.
     */
    private BigDecimal profitFactor(List<OvernightBacktestRow> rows) {
        BigDecimal gains = rows.stream()
                .map(OvernightBacktestRow::closeReturnRate)
                .filter(Objects::nonNull)
                .filter(value -> value.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal losses = rows.stream()
                .map(OvernightBacktestRow::closeReturnRate)
                .filter(Objects::nonNull)
                .filter(value -> value.signum() < 0)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (losses.signum() == 0)
            return gains.signum() == 0 ? null : bd("999.999999");
        return gains.divide(losses, 6, RoundingMode.HALF_UP);
    }

    /**
     * 하는 일: 가장 많은 후보가 몰린 하루가 전체 표본에서 차지하는 비율(%)을 구해요.
     * 받는 것: 행 목록, 날짜별 건수. 돌려주는 것: 백분율, 행이 없으면 null.
     */
    private BigDecimal maxDateConcentration(List<OvernightBacktestRow> rows, Map<LocalDate, Integer> byDate) {
        if (rows.isEmpty())
            return null;
        int max = byDate.values().stream().max(Integer::compareTo).orElse(0);
        return ratio(max, rows.size());
    }

    /**
     * 하는 일: 완료 표본 수로 신뢰도 등급을 정해요(50 이상 HIGH, 20 이상 MEDIUM, 그 외 LOW).
     * 받는 것: 완료 건수. 돌려주는 것: 등급 문자열.
     */
    private String confidence(int completed) {
        if (completed >= 50)
            return "HIGH";
        if (completed >= 20)
            return "MEDIUM";
        return "LOW";
    }

    /**
     * 하는 일: 신뢰도 등급을 비교용 숫자로 바꿔요(HIGH=3, MEDIUM=2, 그 외 1).
     * 받는 것: 등급 문자열. 돌려주는 것: 순위 숫자.
     */
    private int confidenceRank(String confidence) {
        return switch (confidence) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }

    /**
     * 하는 일: 행들에서 꺼낸 값의 평균을 구해요(null은 빼요).
     * 받는 것: 행 목록, 값 꺼내는 함수. 돌려주는 것: 소수 6자리 평균, 값이 없으면 null.
     */
    private BigDecimal avg(List<OvernightBacktestRow> rows, Function<OvernightBacktestRow, BigDecimal> getter) {
        List<BigDecimal> values = rows.stream().map(getter).filter(Objects::nonNull).toList();
        if (values.isEmpty())
            return null;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    /**
     * 하는 일: 행들에서 꺼낸 값 중 0보다 큰 비율(승률, %)을 구해요.
     * 받는 것: 행 목록, 값 꺼내는 함수. 돌려주는 것: 승률, 값이 없으면 null.
     */
    private BigDecimal win(List<OvernightBacktestRow> rows, Function<OvernightBacktestRow, BigDecimal> getter) {
        List<BigDecimal> values = rows.stream().map(getter).filter(Objects::nonNull).toList();
        if (values.isEmpty())
            return null;
        long wins = values.stream().filter(value -> value.signum() > 0).count();
        return BigDecimal.valueOf(wins).multiply(bd("100"))
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    /**
     * 하는 일: 기준가 대비 가격 변화율(%)을 구해요.
     * 받는 것: 가격, 기준가. 돌려주는 것: 소수 6자리 변화율, 값이 없거나 기준가가 0이면 null.
     */
    private BigDecimal pct(BigDecimal price, BigDecimal base) {
        if (price == null || base == null || base.signum() == 0)
            return null;
        return price.subtract(base)
                .divide(base, 8, RoundingMode.HALF_UP)
                .multiply(bd("100"))
                .setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * 하는 일: null을 0으로 바꿔요.
     * 받는 것: 숫자(없을 수 있음). 돌려주는 것: 숫자 또는 0.
     */
    private BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 하는 일: 문자열로 BigDecimal을 만들어요.
     * 받는 것: 숫자 문자열. 돌려주는 것: BigDecimal.
     */
    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /** 탐지 한 건과 그 점수 결과를 함께 묶은 값이에요. */
    private record ScoredDetection(ScannerDetection detection, ScoreResult score) {
    }

    /** 청산 방식 한 건의 결과예요: 비용 전·후 수익률, 익절·손절·애매함 여부, 비용 적용 여부. */
    private record StrategyResult(BigDecimal returnRate, BigDecimal netReturnRate,
            boolean targetHit, boolean stopHit, boolean ambiguous, boolean costsApplied) {
    }

    /** 비교할 추천 알고리즘 설정이에요: 코드, 화면 이름, 최소 기회 점수, 최대 위험 점수. */
    private record AlgorithmProfile(String code, String label, BigDecimal minOpportunity, BigDecimal maxRisk) {
    }
}
