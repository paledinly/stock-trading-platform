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

@Service
@Transactional(readOnly = true)
public class OvernightBacktestService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalTime CUTOFF = LocalTime.of(14, 30);
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

    public OvernightBacktestResponse run(LocalDate from, LocalDate to, int limit, BigDecimal minOpportunity,
            BigDecimal maxRisk, BigDecimal targetRate, BigDecimal stopRate) {
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
            tradingDays++;
            List<ScoredDetection> candidates = precisionEvaluator.ranked(source, cutoffAt, asOf,
                    minOpportunityValue, maxRiskValue, safeLimit).stream()
                    .map(row -> new ScoredDetection(row.detection(), row.score()))
                    .toList();
            for (int index = 0; index < candidates.size(); index++) {
                evaluations.add(evaluate(date, index + 1, candidates.get(index), target, stop));
            }
        }

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

    private List<RecommendationAlgorithmSummary> algorithmSummaries(LocalDate start, LocalDate end, int limit,
            BigDecimal minOpportunity, BigDecimal maxRisk, BigDecimal targetRate, BigDecimal stopRate,
            Map<LocalDate, List<ScannerDetection>> byDate) {
        List<AlgorithmProfile> profiles = List.of(
                new AlgorithmProfile("SCANNER_BASELINE", "기존 탐지 점수", minOpportunity, maxRisk),
                new AlgorithmProfile("CLOSING_NO_MA", "마감 점수", minOpportunity, maxRisk),
                new AlgorithmProfile("CLOSING_MA", "마감 점수 + 이평선", minOpportunity, maxRisk),
                new AlgorithmProfile("CLOSING_MA_STRICT", "보수형 이평선", minOpportunity.add(bd("10")),
                        maxRisk.subtract(bd("10")).max(BigDecimal.ZERO)));
        List<RecommendationAlgorithmSummary> summaries = profiles.stream()
                .map(profile -> summarizeAlgorithm(profile,
                        evaluationsForProfile(start, end, limit, profile, targetRate, stopRate, byDate)))
                .toList();
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

    private List<BacktestEvaluation> evaluationsForProfile(LocalDate start, LocalDate end, int limit,
            AlgorithmProfile profile, BigDecimal targetRate, BigDecimal stopRate,
            Map<LocalDate, List<ScannerDetection>> byDate) {
        List<BacktestEvaluation> values = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            Instant cutoffAt = date.atTime(properties.evaluationStart()).atZone(MARKET_ZONE).toInstant();
            Instant asOf = date.atTime(properties.featureFreezeAt()).atZone(MARKET_ZONE).toInstant();
            List<ScoredDetection> candidates = precisionEvaluator.representatives(
                    byDate.getOrDefault(date, List.of()), cutoffAt, asOf,
                    profile.minOpportunity(), profile.maxRisk()).stream()
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

    private ScoreResult scoreFor(AlgorithmProfile profile, ScannerDetection detection) {
        if ("SCANNER_BASELINE".equals(profile.code())) {
            BigDecimal score = value(detection.getScore()).add(value(detection.getOpportunityScore()))
                    .subtract(value(detection.getRiskScore()).multiply(bd("0.35")))
                    .max(BigDecimal.ZERO)
                    .min(bd("100"))
                    .setScale(3, RoundingMode.HALF_UP);
            return new ScoreResult(score, "{}", "{}");
        }
        if ("CLOSING_NO_MA".equals(profile.code())) {
            return scorer.score(detection);
        }
        return scorer.score(detection, intradayMa.calculate(detection), dailyMa.calculate(detection));
    }

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

    private Map<LocalDate, List<ScannerDetection>> groupByDate(List<ScannerDetection> rows) {
        Map<LocalDate, List<ScannerDetection>> byDate = new LinkedHashMap<>();
        for (ScannerDetection row : rows) {
            byDate.computeIfAbsent(row.getSessionDate(), ignored -> new ArrayList<>()).add(row);
        }
        return byDate;
    }

    private Map<Long, ScannerDetection> deduplicateByStock(List<ScannerDetection> source) {
        Map<Long, ScannerDetection> latest = new LinkedHashMap<>();
        for (ScannerDetection detection : source) {
            latest.putIfAbsent(detection.getStock().getId(), detection);
        }
        return latest;
    }

    private boolean qualifies(ScannerDetection detection, BigDecimal minOpportunity, BigDecimal maxRisk) {
        return value(detection.getOpportunityScore()).compareTo(minOpportunity) >= 0
                && value(detection.getRiskScore()).compareTo(maxRisk) <= 0
                && detection.getStock().isActive()
                && !detection.getStock().isManaged()
                && !detection.getStock().isTradingHalted()
                && !detection.getStock().isEtf()
                && !detection.getStock().isEtn();
    }

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

    private StockCandle entryCandle(ScannerDetection detection, LocalDate recommendationDate) {
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

    private List<StockCandle> nextSessionCandles(ScannerDetection detection, LocalDate recommendationDate) {
        LocalDate nextDate = calendar.nextTradingDay(recommendationDate);
        Instant from = calendar.open(nextDate);
        Instant to = calendar.close(nextDate).plusSeconds(1);
        List<StockCandle> values = candles
                .findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        detection.getStock().getId(), TIMEFRAME, from, to)
                .stream()
                .filter(StockCandle::isFinalCandle)
                .toList();
        if (values.isEmpty())
            return List.of();
        return values.stream()
                .filter(candle -> candle.getStartTime().atZone(MARKET_ZONE).toLocalDate().equals(nextDate))
                .toList();
    }

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
        return new OvernightExitStrategySummary(
                strategy,
                label,
                values.size(),
                ratio(wins, values.size()),
                values.stream().map(StrategyResult::returnRate).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP),
                values.stream().map(StrategyResult::netReturnRate).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP),
                avg(evaluations.stream()
                        .map(BacktestEvaluation::row)
                        .filter(row -> "COMPLETED".equals(row.status()))
                        .toList(), OvernightBacktestRow::maxDrawdownRate),
                ratio(targetHits, values.size()),
                ratio(stopHits, values.size()),
                ambiguous,
                values.stream().anyMatch(StrategyResult::costsApplied),
                OvernightExecutionSimulator.VERSION);
    }

    private StrategyResult nextOpen(BacktestEvaluation evaluation) {
        if (!completed(evaluation))
            return null;
        StockCandle first = evaluation.candles().getFirst();
        return result(execution.exit(evaluation.row().buyReferencePrice(), first.getStartTime(), first.getOpen(),
                "NEXT_OPEN", evaluation.row().targetHit(), evaluation.row().stopHit(), false));
    }

    private StrategyResult nextClose(BacktestEvaluation evaluation) {
        if (!completed(evaluation))
            return null;
        StockCandle last = evaluation.candles().getLast();
        return result(execution.exit(evaluation.row().buyReferencePrice(), last.getStartTime(), last.getClose(),
                "NEXT_CLOSE", evaluation.row().targetHit(), evaluation.row().stopHit(), false));
    }

    private StrategyResult targetOrStop(BacktestEvaluation evaluation, BigDecimal targetRate, BigDecimal stopRate) {
        if (!completed(evaluation))
            return null;
        return result(execution.targetOrStop(evaluation.row().buyReferencePrice(), evaluation.candles(),
                targetRate, stopRate));
    }

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
        StockCandle last = evaluation.candles().getLast();
        return result(execution.exit(base, last.getStartTime(), last.getClose(),
                "TIME_EXIT", targetReached, false, ambiguous));
    }

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

    private boolean completed(BacktestEvaluation evaluation) {
        return "COMPLETED".equals(evaluation.row().status()) && !evaluation.candles().isEmpty();
    }

    private StrategyResult result(ExecutionResult value) {
        return value == null ? null : new StrategyResult(value.grossReturnRate(), value.netReturnRate(),
                value.targetHit(), value.stopHit(), value.ambiguous(), value.costsApplied());
    }

    private BigDecimal threshold(BigDecimal base, BigDecimal rate) {
        return base.multiply(BigDecimal.ONE.add(rate.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)));
    }

    private BigDecimal ma(List<BigDecimal> values, int period) {
        return values.subList(values.size() - period, values.size()).stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(long count, int total) {
        if (total == 0)
            return null;
        return BigDecimal.valueOf(count).multiply(bd("100"))
                .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(List<OvernightBacktestRow> rows, Predicate<OvernightBacktestRow> predicate) {
        if (rows.isEmpty())
            return null;
        return ratio(rows.stream().filter(predicate).count(), rows.size());
    }

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

    private BigDecimal maxDateConcentration(List<OvernightBacktestRow> rows, Map<LocalDate, Integer> byDate) {
        if (rows.isEmpty())
            return null;
        int max = byDate.values().stream().max(Integer::compareTo).orElse(0);
        return ratio(max, rows.size());
    }

    private String confidence(int completed) {
        if (completed >= 50)
            return "HIGH";
        if (completed >= 20)
            return "MEDIUM";
        return "LOW";
    }

    private int confidenceRank(String confidence) {
        return switch (confidence) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }

    private BigDecimal avg(List<OvernightBacktestRow> rows, Function<OvernightBacktestRow, BigDecimal> getter) {
        List<BigDecimal> values = rows.stream().map(getter).filter(Objects::nonNull).toList();
        if (values.isEmpty())
            return null;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal win(List<OvernightBacktestRow> rows, Function<OvernightBacktestRow, BigDecimal> getter) {
        List<BigDecimal> values = rows.stream().map(getter).filter(Objects::nonNull).toList();
        if (values.isEmpty())
            return null;
        long wins = values.stream().filter(value -> value.signum() > 0).count();
        return BigDecimal.valueOf(wins).multiply(bd("100"))
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal pct(BigDecimal price, BigDecimal base) {
        if (price == null || base == null || base.signum() == 0)
            return null;
        return price.subtract(base)
                .divide(base, 8, RoundingMode.HALF_UP)
                .multiply(bd("100"))
                .setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private record ScoredDetection(ScannerDetection detection, ScoreResult score) {
    }

    private record StrategyResult(BigDecimal returnRate, BigDecimal netReturnRate,
            boolean targetHit, boolean stopHit, boolean ambiguous, boolean costsApplied) {
    }

    private record AlgorithmProfile(String code, String label, BigDecimal minOpportunity, BigDecimal maxRisk) {
    }
}
