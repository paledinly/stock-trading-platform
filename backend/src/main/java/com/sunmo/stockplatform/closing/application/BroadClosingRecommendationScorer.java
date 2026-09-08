package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.application.ClosingRecommendationScorer.ScoreResult;
import com.sunmo.stockplatform.marketwide.domain.MarketBroadSnapshot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class BroadClosingRecommendationScorer {
    static final BigDecimal MAX_SCORE = new BigDecimal("65");
    private final ObjectMapper objectMapper;

    public BroadClosingRecommendationScorer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ScoreResult score(MarketBroadSnapshot snapshot, List<String> missing) {
        Map<String, BigDecimal> factors = new LinkedHashMap<>();
        factors.put("broadRank", cap(value(snapshot.getBroadScore()).multiply(bd("0.45")), "40"));
        factors.put("liquidity", snapshot.getAccumulatedTradingValue() == null ? BigDecimal.ZERO
                : cap(snapshot.getAccumulatedTradingValue().divide(bd("100000000"), 6, RoundingMode.HALF_UP), "15"));
        factors.put("positiveMomentum", snapshot.getChangeRate() == null ? BigDecimal.ZERO
                : cap(snapshot.getChangeRate().max(BigDecimal.ZERO).multiply(bd("1.5")), "10"));
        BigDecimal raw = factors.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal qualityPenalty = BigDecimal.valueOf(missing.size()).multiply(bd("4"));
        BigDecimal score = raw.subtract(qualityPenalty).max(BigDecimal.ZERO).min(MAX_SCORE)
                .setScale(3, RoundingMode.HALF_UP);
        return new ScoreResult(score, json("recommendation", snapshot, factors, missing),
                json("risk", snapshot, Map.of("missingFeaturePenalty", qualityPenalty), missing));
    }

    private String json(String kind, MarketBroadSnapshot snapshot, Map<String, BigDecimal> factors,
            List<String> missing) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", "closing-recommendation-v3-broad-precision");
        payload.put("kind", kind);
        payload.put("source", "broad_snapshot");
        payload.put("snapshotId", snapshot.getId());
        payload.put("rankingSources", snapshot.getRankingSources());
        payload.put("dataQuality", snapshot.getDataQuality().name());
        payload.put("missingFeatures", missing);
        payload.put("factors", factors);
        try { return objectMapper.writeValueAsString(payload); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Failed to serialize broad score", e); }
    }

    private BigDecimal cap(BigDecimal value, String max) { return value.max(BigDecimal.ZERO).min(bd(max)); }
    private BigDecimal value(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private BigDecimal bd(String value) { return new BigDecimal(value); }
}
