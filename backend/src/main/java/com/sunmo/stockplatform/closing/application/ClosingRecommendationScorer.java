/*
 * 정밀(PRECISION) 후보 탐지 1건의 마감 추천 점수(0~100)를 계산해요.
 * 기회 요소(기본 기회 점수, 마감 근접도, 거래대금, VWAP 위치, 고가 근접, 거래량 증가, 장중·일봉 이동평균)에서
 * 위험 요소(기본 위험 점수, 과열, 하락 모멘텀, 고가 이탈, 약한 체결강도, 이동평균 이탈)를 빼요.
 * ClosingPrecisionEvaluator, ClosingRecommendationService, OvernightBacktestService, BroadClosingRecommendationScorer(결과 타입)가 써요.
 */
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

/**
 * 마감 추천 점수 계산기예요. 점수는 후보끼리 줄 세우기 위한 상대 점수이고 상승 확률이 아니에요.
 * 가산 항목과 감점 항목을 각각 상한을 둔 채 더하고 빼서 총점을 내는 채점표 같아요.
 * 항목별 점수를 근거 JSON으로 같이 남겨 나중에 왜 그 점수가 나왔는지 볼 수 있어요.
 */
@Component
public class ClosingRecommendationScorer {
    // 마감 근접도 점수를 계산하는 기준 구간(14:30~15:30, 한국 시간)이에요.
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalTime SCORE_START = LocalTime.of(14, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private final ObjectMapper objectMapper;

    /**
     * 근거 JSON을 만들기 위한 ObjectMapper를 받아요.
     */
    public ClosingRecommendationScorer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 이동평균 Feature 없이 점수를 계산해요(이동평균 관문 제거 실험 경로).
     * 받는 것: 탐지. 돌려주는 것: 점수와 근거 JSON.
     */
    public ScoreResult score(ScannerDetection detection) {
        return score(detection, IntradayMovingAverageFeature.empty(0), DailyMovingAverageFeature.empty(0));
    }

    /**
     * 탐지와 장중·일봉 이동평균 Feature로 점수를 계산해요.
     * 받는 것: 탐지, 장중 이동평균 Feature, 일봉 이동평균 Feature.
     * 돌려주는 것: 0~100 점수(소수 3자리), 추천 근거 JSON, 위험 근거 JSON을 담은 ScoreResult.
     */
    public ScoreResult score(ScannerDetection detection, IntradayMovingAverageFeature intradayMa,
            DailyMovingAverageFeature dailyMa) {
        // 준비: 탐지 당시 Feature 스냅샷에서 VWAP 거리, 고가 거리, 체결강도 등을 꺼내요.
        FeatureValues feature = featureValues(detection.getFeatureSnapshot());
        Map<String, BigDecimal> opportunity = new LinkedHashMap<>();
        Map<String, BigDecimal> risk = new LinkedHashMap<>();

        // 기회 요소: 항목마다 상한을 둬서 한 요소가 점수를 독차지하지 못하게 해요. 이동평균 항목은 Feature가 준비됐을 때만 더해요.
        opportunity.put("baseOpportunity", cap(value(detection.getOpportunityScore()).multiply(bd("0.45")), "35"));
        opportunity.put("closingRecency", closingRecency(detection.getDetectedAt()));
        opportunity.put("liquidity", cap(value(detection.getDailyValue()).divide(bd("100000000"), 6, RoundingMode.HALF_UP), "15"));
        opportunity.put("vwapPosition", feature.vwapDistanceRate() == null ? BigDecimal.ZERO
                : cap(positive(feature.vwapDistanceRate()).multiply(bd("5")), "15"));
        opportunity.put("dayHighProximity", dayHighProximity(feature.dayHighDistanceRate()));
        opportunity.put("volumeExpansion", cap(positive(value(detection.getVolumeRatio()).subtract(BigDecimal.ONE)).multiply(bd("5")), "10"));
        opportunity.put("intradayBullishAlignment", intradayMa.ready() && intradayMa.bullishAlignment() ? bd("8") : BigDecimal.ZERO);
        opportunity.put("intradayGoldenCross", intradayMa.ready() && intradayMa.goldenCross() ? bd("5") : BigDecimal.ZERO);
        opportunity.put("intradayMa20Support", intradayMa.ready() && intradayMa.ma20Support() ? bd("4") : BigDecimal.ZERO);
        opportunity.put("dailyTrendAlignment", dailyMa.ready() && dailyMa.bullishAlignment() ? bd("8") : BigDecimal.ZERO);
        opportunity.put("dailyMa20Rising", dailyMa.ready() && dailyMa.ma20Rising() ? bd("5") : BigDecimal.ZERO);
        opportunity.put("dailyCloseAboveMa20", dailyMa.ready() && dailyMa.closeAboveMa20() ? bd("4") : BigDecimal.ZERO);

        // 위험 요소: 높을수록 불리한 조건이 많다는 뜻이에요.
        risk.put("baseRisk", cap(value(detection.getRiskScore()).multiply(bd("0.45")), "40"));
        risk.put("vwapOverextension", feature.vwapDistanceRate() == null ? BigDecimal.ZERO
                : cap(positive(feature.vwapDistanceRate().subtract(bd("5"))).multiply(bd("5")), "20"));
        risk.put("lateNegativeMomentum", detection.getChangeRate() != null && detection.getChangeRate().signum() < 0 ? bd("10") : BigDecimal.ZERO);
        risk.put("farFromDayHigh", feature.dayHighDistanceRate() == null ? BigDecimal.ZERO
                : cap(positive(feature.dayHighDistanceRate().subtract(bd("3"))).multiply(bd("4")), "15"));
        risk.put("weakTradeStrength", feature.tradeStrength() == null ? BigDecimal.ZERO
                : cap(positive(bd("100").subtract(feature.tradeStrength())).multiply(bd("0.15")), "15"));
        risk.put("intradayMa20Breakdown", intradayMa.ready() && intradayMa.ma20Broken() ? bd("12") : BigDecimal.ZERO);
        risk.put("dailyTrendWeakness", dailyTrendWeakness(dailyMa));
        risk.put("dailyMaOverextension", dailyMa.ready() && dailyMa.overextendedFromMa20() ? bd("10") : BigDecimal.ZERO);

        // 결과: 기회 합계 - 위험 합계를 0~100으로 자르고 근거 JSON과 함께 돌려줘요.
        BigDecimal total = total(opportunity).subtract(total(risk)).max(BigDecimal.ZERO).min(bd("100"))
                .setScale(3, RoundingMode.HALF_UP);
        return new ScoreResult(total, json("recommendation", detection, opportunity, feature, intradayMa, dailyMa),
                json("risk", detection, risk, feature, intradayMa, dailyMa));
    }

    /**
     * 탐지 시각이 14:30~15:30 구간에서 늦을수록 최대 15점까지 주는 마감 근접도 점수예요.
     * 받는 것: 탐지 시각. 돌려주는 것: 0~15점(14:30 전이면 0).
     * 기본 입력 마감 15:15에서는 최대 약 11.25점이에요.
     */
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

    /**
     * 당일 고가에 가까울수록 높은 점수를 줘요(거리 0%면 10점, 1%마다 4점씩 감소).
     * 받는 것: 고가 대비 거리(%). 돌려주는 것: 0~10점, 값이 없으면 0.
     */
    private BigDecimal dayHighProximity(BigDecimal dayHighDistanceRate) {
        if (dayHighDistanceRate == null)
            return BigDecimal.ZERO;
        return cap(bd("10").subtract(dayHighDistanceRate.multiply(bd("4"))), "10");
    }

    /**
     * 요소 점수를 모두 더해요.
     * 받는 것: 요소별 점수. 돌려주는 것: 합계.
     */
    private BigDecimal total(Map<String, BigDecimal> factors) {
        return factors.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 일봉 추세가 약한 만큼 감점해요(종가<MA20 6점, MA5<MA20 4점, MA20 하락 3점, 최대 10점).
     * 받는 것: 일봉 이동평균 Feature. 돌려주는 것: 감점, 준비 안 됐으면 0.
     */
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

    /**
     * 점수 근거를 저장용 JSON으로 만들어요.
     * 받는 것: 종류(recommendation/risk), 탐지, 요소별 점수, Feature 값, 장중·일봉 이동평균.
     * 돌려주는 것: 전략 버전·출처·요소·Feature가 들어간 JSON 문자열.
     */
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

    /**
     * 요소 점수를 소수 3자리 문자열로 바꿔요.
     * 받는 것: 요소별 점수. 돌려주는 것: 요소별 문자열 Map.
     */
    private Map<String, String> plainFactors(Map<String, BigDecimal> factors) {
        Map<String, String> values = new LinkedHashMap<>();
        factors.forEach((key, value) -> values.put(key, value.setScale(3, RoundingMode.HALF_UP).toPlainString()));
        return values;
    }

    /**
     * Feature 스냅샷 JSON에서 점수에 쓰는 값을 꺼내요.
     * 받는 것: JSON 문자열. 돌려주는 것: Feature 값, 비었거나 JSON이 깨졌으면 모두 null인 값.
     */
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

    /**
     * JSON 필드를 숫자로 읽어요.
     * 받는 것: JSON 노드, 필드 이름. 돌려주는 것: 숫자, 없거나 비었으면 null.
     * 숫자로 읽을 수 없는 값도 필드가 없을 때와 똑같이 null(자료 없음)로 돌려줘요.
     * 값 하나가 깨져도 그 종목의 그 요소만 빠지고, 15:15 평가 전체는 멈추지 않아요.
     */
    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank())
            return null;
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // 작은 도우미들: value는 null을 0으로, positive는 음수를 0으로, cap은 0~상한으로 자르기, bd는 문자열을 BigDecimal로 바꿔요.
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

    /** 점수 결과예요. 총점, 추천 근거 JSON, 위험 근거 JSON을 담아요. */
    public record ScoreResult(BigDecimal score, String recommendationReason, String riskReason) {
    }

    /** 점수에 쓰는 Feature 스냅샷 값(VWAP 거리, 고가 거리, 체결강도, 회전율)이에요. */
    private record FeatureValues(BigDecimal vwapDistanceRate, BigDecimal dayHighDistanceRate,
            BigDecimal tradeStrength, BigDecimal turnoverRatio) {
        // empty(): 모두 비어 있는 값을 만들어요. toMap(): 근거 JSON용 문자열 Map으로 바꿔요.
        private static FeatureValues empty() {
            return new FeatureValues(null, null, null, null);
        }

        private Map<String, String> toMap() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("vwapDistanceRate", text(vwapDistanceRate));
            values.put("dayHighDistanceRate", text(dayHighDistanceRate));
            values.put("tradeStrength", text(tradeStrength));
            values.put("turnoverRatio", text(turnoverRatio));
            return values;
        }

        private String text(BigDecimal value) { return value == null ? null : value.toPlainString(); }
    }
}
