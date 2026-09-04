package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.GenerateResponse;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.RecommendationResponse;
import com.sunmo.stockplatform.closing.application.ClosingRecommendationScorer.ScoreResult;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRepository;
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
    private final ClosingRecommendationScorer scorer;
    private final IntradayMovingAverageService intradayMa;
    private final DailyMovingAverageService dailyMa;

    public ClosingRecommendationService(ScannerDetectionRepository detections,
            ClosingRecommendationRepository recommendations, ClosingRecommendationScorer scorer,
            IntradayMovingAverageService intradayMa, DailyMovingAverageService dailyMa) {
        this.detections = detections;
        this.recommendations = recommendations;
        this.scorer = scorer;
        this.intradayMa = intradayMa;
        this.dailyMa = dailyMa;
    }

    @Transactional
    public GenerateResponse generate(LocalDate date, int limit, BigDecimal minOpportunity, BigDecimal maxRisk) {
        LocalDate targetDate = date == null ? LocalDate.now(MARKET_ZONE) : date;
        Instant generatedAt = Instant.now();
        int safeLimit = Math.min(Math.max(limit, 1), 30);
        Instant cutoff = targetDate.atTime(DEFAULT_CUTOFF).atZone(MARKET_ZONE).toInstant();
        List<ScannerDetection> source = detections
                .findBySessionDateAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(targetDate, cutoff);

        List<ScannerDetection> latestByStock = new ArrayList<>(deduplicateByStock(source).values());
        List<ScoredCandidate> candidates = latestByStock.stream()
                .filter(detection -> qualifies(detection, minOpportunity, maxRisk))
                .map(detection -> new ScoredCandidate(detection,
                        scorer.score(detection, intradayMa.calculate(detection), dailyMa.calculate(detection))))
                .sorted(Comparator.comparing((ScoredCandidate item) -> item.score().score()).reversed()
                        .thenComparing(item -> item.detection().getDetectedAt(), Comparator.reverseOrder()))
                .limit(safeLimit)
                .toList();

        recommendations.deleteByRecommendationDate(targetDate);
        List<ClosingRecommendation> ranked = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            ScoredCandidate candidate = candidates.get(index);
            ranked.add(candidate(targetDate, generatedAt, candidate.detection(), candidate.score(), index + 1));
        }
        List<ClosingRecommendation> saved = recommendations.saveAll(ranked);
        return new GenerateResponse(
                targetDate,
                generatedAt,
                source.size(),
                saved.size(),
                ClosingRecommendation.STRATEGY_VERSION,
                saved.stream().map(RecommendationResponse::from).toList());
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
                && detection.getStock().isActive()
                && !detection.getStock().isManaged()
                && !detection.getStock().isTradingHalted()
                && !detection.getStock().isEtf()
                && !detection.getStock().isEtn();
    }

    private ClosingRecommendation candidate(LocalDate date, Instant generatedAt, ScannerDetection detection,
            ScoreResult score, int rank) {
        return new ClosingRecommendation(
                date,
                generatedAt,
                detection,
                rank,
                score.score(),
                score.recommendationReason(),
                score.riskReason());
    }

    private BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private record ScoredCandidate(ScannerDetection detection, ScoreResult score) {
    }
}
