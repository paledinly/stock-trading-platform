package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ClosingRecommendationScorer {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalTime SCORE_START = LocalTime.of(14, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private final ObjectMapper objectMapper;

    public ClosingRecommendationScorer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ScoreResult score(ScannerDetection detection) {
        return score(detection, IntradayMovingAverageFeature.empty(0), DailyMovingAverageFeature.empty(0));
    }

    public ScoreResult score(ScannerDetection detection, IntradayMovingAverageFeature intradayMa) {
        return score(detection, intradayMa, DailyMovingAverageFeature.empty(0));
    }

    public ScoreResult score(ScannerDetection detection, IntradayMovingAverageFeature intradayMa,
            DailyMovingAverageFeature dailyMa) {
        FeatureValues feature = featureValues(detection.getFeatureSnapshot());
        Map<String, BigDecimal> opportunity = new LinkedHashMap<>();
        Map<String, BigDecimal> risk = new LinkedHashMap<>();

        opportunity.put("baseOpportunity", cap(value(detection.getOpportunityScore()).multiply(bd("0.45")), "35"));
        opportunity.put("closingRecency", closingRecency(detection.getDetectedAt()));
        opportunity.put("liquidity", cap(value(detection.getDailyValue()).divide(bd("100000000"), 6, RoundingMode.HALF_UP), "15"));
        opportunity.put("vwapPosition", cap(positive(feature.vwapDistanceRate()).multiply(bd("5")), "15"));
        opportunity.put("dayHighProximity", dayHighProximity(feature.dayHighDistanceRate()));
        opportunity.put("volumeExpansion", cap(positive(value(detection.getVolumeRatio()).subtract(BigDecimal.ONE)).multiply(bd("5")), "10"));
        opportunity.put("intradayBullishAlignment", intradayMa.ready() && intradayMa.bullishAlignment() ? bd("8") : BigDecimal.ZERO);
        opportunity.put("intradayGoldenCross", intradayMa.ready() && intradayMa.goldenCross() ? bd("5") : BigDecimal.ZERO);
        opportunity.put("intradayMa20Support", intradayMa.ready() && intradayMa.ma20Support() ? bd("4") : BigDecimal.ZERO);
        opportunity.put("dailyTrendAlignment", dailyMa.ready() && dailyMa.bullishAlignment() ? bd("8") : BigDecimal.ZERO);
        opportunity.put("dailyMa20Rising", dailyMa.ready() && dailyMa.ma20Rising() ? bd("5") : BigDecimal.ZERO);
        opportunity.put("dailyCloseAboveMa20", dailyMa.ready() && dailyMa.closeAboveMa20() ? bd("4") : BigDecimal.ZERO);

        risk.put("baseRisk", cap(value(detection.getRiskScore()).multiply(bd("0.45")), "40"));
        risk.put("vwapOverextension", cap(positive(feature.vwapDistanceRate().subtract(bd("5"))).multiply(bd("5")), "20"));
        risk.put("lateNegativeMomentum", detection.getChangeRate() != null && detection.getChangeRate().signum() < 0 ? bd("10") : BigDecimal.ZERO);
        risk.put("farFromDayHigh", cap(positive(feature.dayHighDistanceRate().subtract(bd("3"))).multiply(bd("4")), "15"));
        risk.put("weakTradeStrength", cap(positive(bd("100").subtract(feature.tradeStrength())).multiply(bd("0.15")), "15"));
        risk.put("intradayMa20Breakdown", intradayMa.ready() && intradayMa.ma20Broken() ? bd("12") : BigDecimal.ZERO);
        risk.put("dailyTrendWeakness", dailyTrendWeakness(dailyMa));
        risk.put("dailyMaOverextension", dailyMa.ready() && dailyMa.overextendedFromMa20() ? bd("10") : BigDecimal.ZERO);

        BigDecimal total = total(opportunity).subtract(total(risk)).max(BigDecimal.ZERO).min(bd("100"))
                .setScale(3, RoundingMode.HALF_UP);
        return new ScoreResult(total, json("recommendation", detection, opportunity, feature, intradayMa, dailyMa),
                json("risk", detection, risk, feature, intradayMa, dailyMa));
    }

    private BigDecimal closingRecency(Instant detectedAt) {
        LocalTime time = detectedAt.atZone(MARKET_ZONE).toLocalTime();
        if (time.isBefore(SCORE_START))
            return BigDecimal.ZERO;
        long totalMinutes = Duration.between(SCORE_START, MARKET_CLOSE).toMinutes();
        long elapsed = Math.min(totalMinutes, Duration.between(SCORE_START, time).toMinutes());
        return BigDecimal.valueOf(elapsed)
                .multiply(bd("15"))
                .divide(BigDecimal.valueOf(totalMinutes), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal dayHighProximity(BigDecimal dayHighDistanceRate) {
        return cap(bd("10").subtract(dayHighDistanceRate.multiply(bd("4"))), "10");
    }

    private BigDecimal total(Map<String, BigDecimal> factors) {
        return factors.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal dailyTrendWeakness(DailyMovingAverageFeature dailyMa) {
        if (!dailyMa.ready())
            return BigDecimal.ZERO;
        BigDecimal penalty = BigDecimal.ZERO;
        if (!dailyMa.closeAboveMa20())
            penalty = penalty.add(bd("6"));
        if (!dailyMa.ma5AboveMa20())
            penalty = penalty.add(bd("4"));
        if (!dailyMa.ma20Rising())
            penalty = penalty.add(bd("3"));
        return penalty.min(bd("10"));
    }

    private String json(String kind, ScannerDetection detection, Map<String, BigDecimal> factors, FeatureValues feature,
            IntradayMovingAverageFeature intradayMa, DailyMovingAverageFeature dailyMa) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", ClosingRecommendation.STRATEGY_VERSION);
        payload.put("kind", kind);
        payload.put("source", "scanner_detection");
        payload.put("scannerType", detection.getType().name());
        payload.put("detectedAt", detection.getDetectedAt().toString());
        payload.put("factors", plainFactors(factors));
        payload.put("feature", feature.toMap());
        payload.put("intradayMa", intradayMa.toMap());
        payload.put("dailyMa", dailyMa.toMap());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize closing recommendation score", e);
        }
    }

    private Map<String, String> plainFactors(Map<String, BigDecimal> factors) {
        Map<String, String> values = new LinkedHashMap<>();
        factors.forEach((key, value) -> values.put(key, value.setScale(3, RoundingMode.HALF_UP).toPlainString()));
        return values;
    }

    private FeatureValues featureValues(String snapshot) {
        if (snapshot == null || snapshot.isBlank())
            return FeatureValues.empty();
        try {
            JsonNode node = objectMapper.readTree(snapshot);
            return new FeatureValues(
                    decimal(node, "vwapDistanceRate"),
                    decimal(node, "dayHighDistanceRate"),
                    decimal(node, "tradeStrength"),
                    decimal(node, "turnoverRatio"));
        } catch (JsonProcessingException ignored) {
            return FeatureValues.empty();
        }
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank())
            return BigDecimal.ZERO;
        return new BigDecimal(value.asText());
    }

    private BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal positive(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private BigDecimal cap(BigDecimal value, String max) {
        BigDecimal upper = bd(max);
        if (value.compareTo(BigDecimal.ZERO) < 0)
            return BigDecimal.ZERO;
        return value.compareTo(upper) > 0 ? upper : value.setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    public record ScoreResult(BigDecimal score, String recommendationReason, String riskReason) {
    }

    private record FeatureValues(BigDecimal vwapDistanceRate, BigDecimal dayHighDistanceRate,
            BigDecimal tradeStrength, BigDecimal turnoverRatio) {
        private static FeatureValues empty() {
            return new FeatureValues(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        private Map<String, String> toMap() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("vwapDistanceRate", vwapDistanceRate.toPlainString());
            values.put("dayHighDistanceRate", dayHighDistanceRate.toPlainString());
            values.put("tradeStrength", tradeStrength.toPlainString());
            values.put("turnoverRatio", turnoverRatio.toPlainString());
            return values;
        }
    }
}
