package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.GenerateResponse;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.RecommendationResponse;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.CandidateEvaluationResponse;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRunRepository;
import com.sunmo.stockplatform.closing.application.ClosingRecommendationScorer.ScoreResult;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRepository;
import com.sunmo.stockplatform.closing.infrastructure.OvernightPerformanceRepository;
import com.sunmo.stockplatform.closing.infrastructure.OvernightPositionDecisionRepository;
import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.marketwide.domain.BroadSnapshotQuality;
import com.sunmo.stockplatform.marketwide.domain.BroadSnapshotStatus;
import com.sunmo.stockplatform.marketwide.domain.MarketBroadSnapshot;
import com.sunmo.stockplatform.marketwide.infrastructure.MarketBroadSnapshotRepository;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import com.sunmo.stockplatform.scanner.infrastructure.ScannerDetectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class ClosingRecommendationService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private final ScannerDetectionRepository detections;
    private final ClosingRecommendationRepository recommendations;
    private final OvernightPerformanceRepository performances;
    private final OvernightPositionDecisionRepository decisions;
    private final ClosingRecommendationScorer scorer;
    private final IntradayMovingAverageService intradayMa;
    private final DailyMovingAverageService dailyMa;
    private final MarketBroadSnapshotRepository broadSnapshots;
    private final BroadClosingRecommendationScorer broadScorer;
    private final ObjectMapper objectMapper;
    private final StockCandleRepository candles;
    private final ClosingRecommendationProperties properties;
    private final ClosingRecommendationRunRepository runs;

    public ClosingRecommendationService(ScannerDetectionRepository detections,
            ClosingRecommendationRepository recommendations, ClosingRecommendationScorer scorer,
            IntradayMovingAverageService intradayMa, DailyMovingAverageService dailyMa,
            MarketBroadSnapshotRepository broadSnapshots, BroadClosingRecommendationScorer broadScorer,
            ObjectMapper objectMapper, OvernightPerformanceRepository performances,
            OvernightPositionDecisionRepository decisions, StockCandleRepository candles,
            ClosingRecommendationProperties properties, ClosingRecommendationRunRepository runs) {
        this.detections = detections;
        this.recommendations = recommendations;
        this.performances = performances;
        this.decisions = decisions;
        this.scorer = scorer;
        this.intradayMa = intradayMa;
        this.dailyMa = dailyMa;
        this.broadSnapshots = broadSnapshots;
        this.broadScorer = broadScorer;
        this.objectMapper = objectMapper;
        this.candles = candles;
        this.properties = properties;
        this.runs = runs;
    }

    @Transactional
    public GenerateResponse generate(LocalDate date, int limit, BigDecimal minOpportunity, BigDecimal maxRisk) {
        LocalDate targetDate = date == null ? LocalDate.now(MARKET_ZONE) : date;
        Instant generatedAt = Instant.now();
        if (targetDate.isAfter(generatedAt.atZone(MARKET_ZONE).toLocalDate()))
            throw new com.sunmo.stockplatform.common.error.ApplicationException(
                    com.sunmo.stockplatform.common.error.ErrorCode.INVALID_REQUEST,
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Future recommendation date is not allowed");
        int safeLimit = Math.min(Math.max(limit, 1), 30);
        Instant cutoff = targetDate.atTime(properties.evaluationStart()).atZone(MARKET_ZONE).toInstant();
        Instant close = targetDate.atTime(15, 30).atZone(MARKET_ZONE).toInstant();
        Instant freeze = targetDate.atTime(properties.featureFreezeAt()).atZone(MARKET_ZONE).toInstant();
        Instant sessionEnd = freeze.isBefore(close) ? freeze : close;
        Instant evaluationEnd = targetDate.equals(LocalDate.now(MARKET_ZONE)) && generatedAt.isBefore(sessionEnd)
                ? generatedAt : sessionEnd;
        List<ScannerDetection> source = detections
                .findBySessionDateAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(targetDate, cutoff);

        List<ScannerDetection> boundedDetections = source.stream()
                .filter(detection -> !detection.getDetectedAt().isAfter(evaluationEnd)).toList();
        Map<ScannerDetection, CandleCoverage> coverage = candleCoverage(boundedDetections, cutoff, evaluationEnd);
        List<ScoredCandidate> precisionCandidates = representativeCandidates(boundedDetections, coverage,
                minOpportunity, maxRisk);

        List<MarketBroadSnapshot> broadSource = broadSnapshots.findClosingCandidates(targetDate, cutoff, evaluationEnd);
        List<ScoredCandidate> selectable = new ArrayList<>();
        for (ScoredCandidate candidate : precisionCandidates) {
            CandidateDecision decision = precisionDecision(candidate, minOpportunity, maxRisk);
            if (decision.disposition() == CandidateDisposition.SELECTED) selectable.add(candidate);
        }
        List<ScoredCandidate> rankedCandidates = selectable.stream()
                .sorted(Comparator.comparing((ScoredCandidate item) -> item.score().score()).reversed()
                        .thenComparing(item -> item.candidate().observedAt(), Comparator.reverseOrder()))
                .toList();
        List<ScoredCandidate> candidates = rankedCandidates.stream().limit(safeLimit).toList();

        decisions.deleteByRecommendationDate(targetDate);
        performances.deleteByRecommendationDate(targetDate);
        recommendations.deleteByRecommendationDate(targetDate);
        List<ClosingRecommendation> ranked = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            ScoredCandidate candidate = candidates.get(index);
            ranked.add(entity(targetDate, generatedAt, candidate, index + 1));
        }
        List<ClosingRecommendation> saved = recommendations.saveAll(ranked);
        List<CandidateEvaluationResponse> evaluations = evaluations(boundedDetections, broadSource, coverage,
                precisionCandidates, candidates, minOpportunity, maxRisk);
        Map<String, Integer> decisionReasons = new LinkedHashMap<>();
        evaluations.stream().filter(row -> !row.disposition().equals("SELECTED"))
                .forEach(row -> decisionReasons.merge(row.decisionReason(), 1, Integer::sum));
        GenerateResponse response = new GenerateResponse(
                targetDate,
                generatedAt,
                boundedDetections.size(),
                broadSource.size(),
                saved.size(),
                (int) evaluations.stream().filter(row -> row.disposition().equals("WATCH")).count(),
                (int) evaluations.stream().filter(row -> row.disposition().equals("EXCLUDED")).count(),
                Map.copyOf(decisionReasons),
                ClosingRecommendation.STRATEGY_VERSION,
                saved.stream().map(RecommendationResponse::from).toList(), evaluationEnd,
                Map.of("minimumCoverageMinutes", properties.minimumCoverageMinutes(),
                        "minimumFinalCandles", properties.minimumFinalCandles(),
                        "minimumFinalScore", properties.minimumFinalScore(),
                        "minOpportunity", threshold(minOpportunity), "maxRisk", riskLimit(maxRisk),
                        "limit", safeLimit, "evaluationStart", properties.evaluationStart().toString(),
                        "featureFreezeAt", properties.featureFreezeAt().toString()),
                Map.copyOf(decisionReasons), evaluations);
        try {
            runs.save(new ClosingRecommendationRun(targetDate, generatedAt, ClosingRecommendation.STRATEGY_VERSION,
                    objectMapper.writeValueAsString(response)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Failed to preserve recommendation audit", error);
        }
        return response;
    }

    private List<CandidateEvaluationResponse> evaluations(List<ScannerDetection> source,
            List<MarketBroadSnapshot> broadSource, Map<ScannerDetection, CandleCoverage> coverage,
            List<ScoredCandidate> scoredPrecision, List<ScoredCandidate> selected,
            BigDecimal minOpportunity, BigDecimal maxRisk) {
        List<CandidateEvaluationResponse> result = new ArrayList<>();
        Set<Long> selectedIds = new HashSet<>();
        selected.forEach(row -> selectedIds.add(row.candidate().stock().getId()));
        for (ScoredCandidate candidate : scoredPrecision) {
            ScannerDetection detection = ((ClosingCandidate.Precision) candidate.candidate()).detection();
            Long id = detection.getStock().getId();
            CandleCoverage observed = coverage.getOrDefault(detection, CandleCoverage.empty());
            CandidateDecision decision;
            if (!tradable(detection.getStock())) decision = new CandidateDecision(CandidateDisposition.EXCLUDED, "NOT_TRADABLE");
            else if (!qualifies(detection, minOpportunity, maxRisk))
                decision = new CandidateDecision(CandidateDisposition.EXCLUDED, "OPPORTUNITY_OR_RISK_FILTERED");
            else if (selectedIds.contains(id)) decision = new CandidateDecision(CandidateDisposition.SELECTED, "QUALIFIED");
            else {
                decision = decision(candidate);
                if (decision.disposition() == CandidateDisposition.SELECTED)
                    decision = new CandidateDecision(CandidateDisposition.WATCH, "RANK_LIMIT_WATCH");
            }
            result.add(evaluation(candidate, observed.finalCandles(), decision, detection.getFeatureSnapshot()));
        }
        for (MarketBroadSnapshot snapshot : latestBroadByStock(broadSource).values()) {
            ScoredCandidate candidate = broadCandidate(snapshot);
            String reason = precisionStocksContain(scoredPrecision, snapshot.getStock().getId()) ? "PRECISION_DUPLICATE"
                    : !eligibleBroad(snapshot) ? "INSUFFICIENT_BROAD_DATA"
                    : candidate.candidate().opportunityScore().compareTo(threshold(minOpportunity)) < 0
                        || candidate.candidate().riskScore().compareTo(riskLimit(maxRisk)) > 0
                            ? "OPPORTUNITY_OR_RISK_FILTERED" : "BROAD_WATCH_ONLY";
            result.add(evaluation(candidate, 0, new CandidateDecision(reason.equals("BROAD_WATCH_ONLY")
                    ? CandidateDisposition.WATCH : CandidateDisposition.EXCLUDED, reason), null));
        }
        return List.copyOf(result);
    }

    private boolean precisionStocksContain(List<ScoredCandidate> rows, Long id) {
        return rows.stream().anyMatch(row -> row.candidate().stock().getId().equals(id));
    }

    private CandidateEvaluationResponse evaluation(ScoredCandidate scored, int finalCandles,
            CandidateDecision decision, String featureSnapshot) {
        ClosingCandidate candidate = scored.candidate();
        return new CandidateEvaluationResponse(candidate.stock().getStockCode(), candidate.stock().getStockName(),
                candidate.source().name(), candidate instanceof ClosingCandidate.Precision precision
                        ? precision.detection().getType().name() : null,
                candidate.observedAt(), candidate.referencePrice(), scored.score().score(), candidate.opportunityScore(),
                candidate.riskScore(), candidate.dataQuality(), finalCandles, candidate.coverageMinutes(),
                candidate.missingFeatures(), decision.disposition().name(), decision.reason(),
                scored.score().recommendationReason(), scored.score().riskReason(), featureSnapshot);
    }

    @Transactional(readOnly = true)
    public GenerateResponse latestEvaluation(LocalDate date) {
        LocalDate target = date == null ? LocalDate.now(MARKET_ZONE) : date;
        return runs.findFirstByRecommendationDateOrderByGeneratedAtDescIdDesc(target).map(run -> {
            try { return objectMapper.readValue(run.getResponseSnapshot(), GenerateResponse.class); }
            catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw new IllegalStateException("Failed to read recommendation audit", error);
            }
        }).orElse(null);
    }

    private ScoredCandidate precisionCandidate(ScannerDetection detection, CandleCoverage coverage) {
        List<String> missing = precisionMissing(detection);
        if (coverage.finalCandles() < properties.minimumFinalCandles()) missing = append(missing, "finalCandles");
        ClosingCandidate.Precision candidate = new ClosingCandidate.Precision(detection, coverage.minutes(), missing);
        return new ScoredCandidate(candidate,
                scorer.score(detection, intradayMa.calculate(detection), dailyMa.calculate(detection)));
    }

    private ScoredCandidate broadCandidate(MarketBroadSnapshot snapshot) {
        List<String> missing = broadMissing(snapshot);
        BigDecimal risk = bd("45").add(BigDecimal.valueOf(missing.size() * 5L)).min(bd("100"));
        ClosingCandidate.Broad candidate = new ClosingCandidate.Broad(snapshot, risk, missing);
        return new ScoredCandidate(candidate, broadScorer.score(snapshot, missing));
    }

    private ClosingRecommendation entity(LocalDate date, Instant generatedAt, ScoredCandidate scored, int rank) {
        String missing = json(scored.candidate().missingFeatures());
        if (scored.candidate() instanceof ClosingCandidate.Precision precision) {
            return new ClosingRecommendation(date, generatedAt, precision.detection(), rank, scored.score().score(),
                    scored.score().recommendationReason(), scored.score().riskReason(), precision.dataQuality(),
                    precision.coverageMinutes(), missing);
        }
        ClosingCandidate.Broad broad = (ClosingCandidate.Broad) scored.candidate();
        return new ClosingRecommendation(date, generatedAt, broad.snapshot(), rank, scored.score().score(),
                broad.opportunityScore(), broad.riskScore(), scored.score().recommendationReason(),
                scored.score().riskReason(), missing);
    }

    private Map<Long, MarketBroadSnapshot> latestBroadByStock(List<MarketBroadSnapshot> source) {
        Map<Long, MarketBroadSnapshot> latest = new LinkedHashMap<>();
        source.forEach(snapshot -> latest.putIfAbsent(snapshot.getStock().getId(), snapshot));
        return latest;
    }

    private Map<ScannerDetection, CandleCoverage> candleCoverage(List<ScannerDetection> source, Instant from, Instant to) {
        Map<ScannerDetection, CandleCoverage> result = new HashMap<>();
        source.stream().map(detection -> detection.getStock().getId()).distinct().forEach(stockId -> {
            List<StockCandle> rows = candles
                    .findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                            stockId, "5M", from, to.plusSeconds(1)).stream()
                    .filter(StockCandle::isFinalCandle)
                    .filter(row -> !row.getStartTime().plus(Duration.ofMinutes(5)).isAfter(to))
                    .toList();
            source.stream().filter(detection -> detection.getStock().getId().equals(stockId)).forEach(detection -> {
                Instant end = detection.getDetectedAt().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
                end = end.minusSeconds(end.atZone(MARKET_ZONE).getMinute() % 5 * 60L);
                Set<Instant> starts = new HashSet<>();
                rows.stream().filter(row -> !row.getStartTime().plus(Duration.ofMinutes(5))
                        .isAfter(detection.getDetectedAt())).forEach(row -> starts.add(row.getStartTime()));
                int count = 0;
                for (Instant expected = end.minus(Duration.ofMinutes(5)); starts.contains(expected);
                        expected = expected.minus(Duration.ofMinutes(5))) count++;
                result.put(detection, new CandleCoverage(count, count * 5));
            });
        });
        return result;
    }

    private CandidateDecision decision(ScoredCandidate candidate) {
        ClosingCandidate.Precision precision = (ClosingCandidate.Precision) candidate.candidate();
        if (!precision.missingFeatures().isEmpty())
            return new CandidateDecision(CandidateDisposition.WATCH, "MISSING_REQUIRED_FEATURES");
        if (precision.coverageMinutes() < properties.minimumCoverageMinutes())
            return new CandidateDecision(CandidateDisposition.WATCH, "INSUFFICIENT_INTRADAY_COVERAGE");
        if (candidate.score().score().compareTo(properties.minimumFinalScore()) < 0)
            return new CandidateDecision(CandidateDisposition.EXCLUDED, "LOW_FINAL_SCORE");
        return new CandidateDecision(CandidateDisposition.SELECTED, "");
    }

    private List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }

    private boolean eligibleBroad(MarketBroadSnapshot snapshot) {
        return snapshot.getDataQuality() == BroadSnapshotQuality.BROAD_C
                && snapshot.getCollectionStatus() == BroadSnapshotStatus.COLLECTED
                && snapshot.getCurrentPrice() != null && snapshot.getAccumulatedTradingValue() != null
                && snapshot.getChangeRate() != null && tradable(snapshot.getStock());
    }

    private List<String> broadMissing(MarketBroadSnapshot snapshot) {
        List<String> missing = new ArrayList<>();
        if (snapshot.getCurrentPrice() == null) missing.add("currentPrice");
        if (snapshot.getChangeRate() == null) missing.add("changeRate");
        if (snapshot.getAccumulatedVolume() == null) missing.add("accumulatedVolume");
        if (snapshot.getAccumulatedTradingValue() == null) missing.add("accumulatedTradingValue");
        if (snapshot.getTradeStrength() == null) missing.add("tradeStrength");
        missing.add("vwap");
        missing.add("volumeRatio");
        return List.copyOf(missing);
    }

    private List<String> precisionMissing(ScannerDetection detection) {
        List<String> missing = new ArrayList<>();
        if (detection.getOpportunityScore() == null) missing.add("opportunityScore");
        if (detection.getRiskScore() == null) missing.add("riskScore");
        if (detection.getVolumeRatio() == null) missing.add("volumeRatio");
        try {
            JsonNode node = objectMapper.readTree(detection.getFeatureSnapshot());
            for (String field : List.of("vwapDistanceRate", "dayHighDistanceRate", "tradeStrength"))
                if (!node.hasNonNull(field) || node.path(field).asText().isBlank()) missing.add(field);
        } catch (Exception error) {
            missing.add("featureSnapshot");
        }
        return List.copyOf(missing);
    }

    private String json(List<String> values) {
        try { return objectMapper.writeValueAsString(values); }
        catch (Exception error) { throw new IllegalStateException("Failed to serialize missing features", error); }
    }

    @Transactional(readOnly = true)
    public List<RecommendationResponse> list(LocalDate date) {
        LocalDate targetDate = date == null ? LocalDate.now(MARKET_ZONE) : date;
        return recommendations.findByRecommendationDateOrderByRankAsc(targetDate).stream()
                .map(RecommendationResponse::from)
                .toList();
    }

    private List<ScoredCandidate> representativeCandidates(List<ScannerDetection> source,
            Map<ScannerDetection, CandleCoverage> coverage, BigDecimal minOpportunity, BigDecimal maxRisk) {
        Comparator<ScoredCandidate> priority = Comparator
                .comparingInt((ScoredCandidate row) -> switch (precisionDecision(row, minOpportunity, maxRisk).disposition()) {
                    case SELECTED -> 2;
                    case WATCH -> 1;
                    case EXCLUDED -> 0;
                }).thenComparing(row -> row.score().score())
                .thenComparing(row -> row.candidate().observedAt())
                .thenComparing(row -> Optional.ofNullable(((ClosingCandidate.Precision) row.candidate())
                        .detection().getId()).orElse(0L));
        Map<Long, ScoredCandidate> representatives = new LinkedHashMap<>();
        for (ScannerDetection detection : source) {
            ScoredCandidate row = precisionCandidate(detection, coverage.getOrDefault(detection, CandleCoverage.empty()));
            representatives.merge(detection.getStock().getId(), row,
                    (old, next) -> priority.compare(old, next) >= 0 ? old : next);
        }
        return List.copyOf(representatives.values());
    }

    private CandidateDecision precisionDecision(ScoredCandidate row, BigDecimal minimum, BigDecimal maximum) {
        ScannerDetection detection = ((ClosingCandidate.Precision) row.candidate()).detection();
        if (!tradable(detection.getStock())) return new CandidateDecision(CandidateDisposition.EXCLUDED, "NOT_TRADABLE");
        if (!qualifies(detection, minimum, maximum))
            return new CandidateDecision(CandidateDisposition.EXCLUDED, "OPPORTUNITY_OR_RISK_FILTERED");
        return decision(row);
    }

    private boolean qualifies(ScannerDetection detection, BigDecimal minOpportunity, BigDecimal maxRisk) {
        BigDecimal opportunity = value(detection.getOpportunityScore());
        BigDecimal risk = value(detection.getRiskScore());
        return opportunity.compareTo(minOpportunity == null ? BigDecimal.ZERO : minOpportunity) >= 0
                && risk.compareTo(maxRisk == null ? bd("65") : maxRisk) <= 0
                && tradable(detection.getStock());
    }

    private boolean tradable(com.sunmo.stockplatform.stock.domain.Stock stock) {
        return stock.isActive() && !stock.isManaged() && !stock.isTradingHalted() && !stock.isEtf() && !stock.isEtn();
    }

    private BigDecimal threshold(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private BigDecimal riskLimit(BigDecimal value) { return value == null ? bd("65") : value; }

    private BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private record ScoredCandidate(ClosingCandidate candidate, ScoreResult score) {
    }

    private record CandleCoverage(int finalCandles, int minutes) {
        private static CandleCoverage empty() { return new CandleCoverage(0, 0); }
    }

    private enum CandidateDisposition { SELECTED, WATCH, EXCLUDED }
    private record CandidateDecision(CandidateDisposition disposition, String reason) { }
}
