package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.GenerateResponse;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.RecommendationResponse;
import com.sunmo.stockplatform.closing.application.ClosingRecommendationScorer.ScoreResult;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRepository;
import com.sunmo.stockplatform.closing.infrastructure.OvernightPerformanceRepository;
import com.sunmo.stockplatform.closing.infrastructure.OvernightPositionDecisionRepository;
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
    private static final LocalTime DEFAULT_CUTOFF = LocalTime.of(14, 30);

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

    public ClosingRecommendationService(ScannerDetectionRepository detections,
            ClosingRecommendationRepository recommendations, ClosingRecommendationScorer scorer,
            IntradayMovingAverageService intradayMa, DailyMovingAverageService dailyMa,
            MarketBroadSnapshotRepository broadSnapshots, BroadClosingRecommendationScorer broadScorer,
            ObjectMapper objectMapper, OvernightPerformanceRepository performances,
            OvernightPositionDecisionRepository decisions) {
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
    }

    @Transactional
    public GenerateResponse generate(LocalDate date, int limit, BigDecimal minOpportunity, BigDecimal maxRisk) {
        LocalDate targetDate = date == null ? LocalDate.now(MARKET_ZONE) : date;
        Instant generatedAt = Instant.now();
        int safeLimit = Math.min(Math.max(limit, 1), 30);
        Instant cutoff = targetDate.atTime(DEFAULT_CUTOFF).atZone(MARKET_ZONE).toInstant();
        Instant close = targetDate.atTime(15, 30).atZone(MARKET_ZONE).toInstant();
        Instant evaluationEnd = targetDate.equals(LocalDate.now(MARKET_ZONE)) && generatedAt.isBefore(close)
                ? generatedAt : close;
        List<ScannerDetection> source = detections
                .findBySessionDateAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(targetDate, cutoff);

        List<ScannerDetection> boundedDetections = source.stream()
                .filter(detection -> !detection.getDetectedAt().isAfter(evaluationEnd)).toList();
        Map<Long, Integer> coverage = coverageMinutes(boundedDetections);
        List<ScoredCandidate> precisionCandidates = deduplicateByStock(boundedDetections).values().stream()
                .filter(detection -> qualifies(detection, minOpportunity, maxRisk))
                .map(detection -> precisionCandidate(detection, coverage.getOrDefault(detection.getStock().getId(), 0)))
                .toList();

        Set<Long> precisionStocks = precisionCandidates.stream().map(item -> item.candidate().stock().getId())
                .collect(java.util.stream.Collectors.toSet());
        List<MarketBroadSnapshot> broadSource = broadSnapshots.findClosingCandidates(targetDate, cutoff, evaluationEnd);
        List<ScoredCandidate> broadCandidates = latestBroadByStock(broadSource).values().stream()
                .filter(snapshot -> !precisionStocks.contains(snapshot.getStock().getId()))
                .filter(this::eligibleBroad)
                .map(this::broadCandidate)
                .filter(candidate -> candidate.candidate().opportunityScore().compareTo(threshold(minOpportunity)) >= 0)
                .filter(candidate -> candidate.candidate().riskScore().compareTo(riskLimit(maxRisk)) <= 0)
                .toList();

        List<ScoredCandidate> candidates = java.util.stream.Stream.concat(precisionCandidates.stream(), broadCandidates.stream())
                .sorted(Comparator.comparing((ScoredCandidate item) -> item.score().score()).reversed()
                        .thenComparing(item -> item.candidate().observedAt(), Comparator.reverseOrder()))
                .limit(safeLimit).toList();

        decisions.deleteByRecommendationDate(targetDate);
        performances.deleteByRecommendationDate(targetDate);
        recommendations.deleteByRecommendationDate(targetDate);
        List<ClosingRecommendation> ranked = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            ScoredCandidate candidate = candidates.get(index);
            ranked.add(entity(targetDate, generatedAt, candidate, index + 1));
        }
        List<ClosingRecommendation> saved = recommendations.saveAll(ranked);
        return new GenerateResponse(
                targetDate,
                generatedAt,
                boundedDetections.size(),
                broadSource.size(),
                saved.size(),
                ClosingRecommendation.STRATEGY_VERSION,
                saved.stream().map(RecommendationResponse::from).toList());
    }

    private ScoredCandidate precisionCandidate(ScannerDetection detection, int coverageMinutes) {
        List<String> missing = precisionMissing(detection);
        ClosingCandidate.Precision candidate = new ClosingCandidate.Precision(detection, coverageMinutes, missing);
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

    private Map<Long, Integer> coverageMinutes(List<ScannerDetection> source) {
        Map<Long, Instant> earliest = new HashMap<>();
        Map<Long, Instant> latest = new HashMap<>();
        for (ScannerDetection detection : source) {
            Long id = detection.getStock().getId();
            earliest.merge(id, detection.getDetectedAt(), (a, b) -> a.isBefore(b) ? a : b);
            latest.merge(id, detection.getDetectedAt(), (a, b) -> a.isAfter(b) ? a : b);
        }
        Map<Long, Integer> result = new HashMap<>();
        earliest.forEach((id, start) -> result.put(id,
                Math.max(0, Math.toIntExact(Duration.between(start, latest.get(id)).toMinutes()))));
        return result;
    }

    private boolean eligibleBroad(MarketBroadSnapshot snapshot) {
        return snapshot.getDataQuality() == BroadSnapshotQuality.BROAD_C
                && snapshot.getCollectionStatus() == BroadSnapshotStatus.COLLECTED
                && snapshot.getCurrentPrice() != null && snapshot.getAccumulatedTradingValue() != null
                && snapshot.getChangeRate() != null && tradable(snapshot.getStock());
    }

    private List<String> broadMissing(MarketBroadSnapshot snapshot) {
        List<String> missing = new ArrayList<>();
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

    private Map<Long, ScannerDetection> deduplicateByStock(List<ScannerDetection> source) {
        Map<Long, ScannerDetection> latest = new LinkedHashMap<>();
        for (ScannerDetection detection : source) {
            latest.putIfAbsent(detection.getStock().getId(), detection);
        }
        return latest;
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
}
