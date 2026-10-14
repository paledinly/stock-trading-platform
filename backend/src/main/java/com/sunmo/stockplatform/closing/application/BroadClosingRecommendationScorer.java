/*
 * BROAD 후보(시장 전체 순위 스캔에서 잡힌 종목)의 마감 추천 점수를 매겨요.
 * 순위 점수, 거래대금, 상승률을 각각 상한을 둔 채 더하고, 빠진 입력 수만큼 감점해 0~65점으로 만들어요.
 * ClosingRecommendationService가 BROAD 후보를 평가할 때 써요.
 */
package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.application.ClosingRecommendationScorer.ScoreResult;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import com.sunmo.stockplatform.marketwide.domain.MarketBroadSnapshot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BROAD 후보 점수기예요. BROAD 후보는 최종 추천이 아니라 관찰(WATCH)까지만 가요.
 * 멀리서 본 손님을 "인기 순위, 지갑 두께, 오늘 기분" 세 가지로만 점수 매기고,
 * 정보가 빠진 항목이 있으면 그만큼 깎는 예비 심사원이라고 생각하면 돼요.
 * 점수는 상대 순위용이며 상승 확률이 아니에요.
 */
@Component
public class BroadClosingRecommendationScorer {
    // BROAD 점수 최고치예요. 세 요소 상한(40+15+10)의 합과 같아요.
    static final BigDecimal MAX_SCORE = new BigDecimal("65");
    private final ObjectMapper objectMapper;

    /**
     * 점수 근거를 JSON으로 저장하기 위한 ObjectMapper를 받아요.
     */
    public BroadClosingRecommendationScorer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 시장 전체 스냅샷 1건의 BROAD 점수와 점수 근거 JSON을 만들어요.
     * 받는 것: 순위 스캔 스냅샷, 빠진 Feature 이름 목록.
     * 돌려주는 것: 점수(소수 3자리), 추천 근거 JSON, 위험 근거 JSON을 담은 ScoreResult.
     */
    public ScoreResult score(MarketBroadSnapshot snapshot, List<String> missing) {
        // 처리: 순위 점수×0.45(최대 40), 누적 거래대금 억 원 단위(최대 15), 양의 등락률×1.5(최대 10)를 요소별로 담아요.
        Map<String, BigDecimal> factors = new LinkedHashMap<>();
        factors.put("broadRank", cap(value(snapshot.getBroadScore()).multiply(bd("0.45")), "40"));
        factors.put("liquidity", snapshot.getAccumulatedTradingValue() == null ? BigDecimal.ZERO
                : cap(snapshot.getAccumulatedTradingValue().divide(bd("100000000"), 6, RoundingMode.HALF_UP), "15"));
        factors.put("positiveMomentum", snapshot.getChangeRate() == null ? BigDecimal.ZERO
                : cap(snapshot.getChangeRate().max(BigDecimal.ZERO).multiply(bd("1.5")), "10"));
        // 결과: 빠진 Feature 1개당 4점을 빼고 0~65점 범위로 자른 뒤 근거 JSON과 함께 돌려줘요.
        BigDecimal raw = factors.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal qualityPenalty = BigDecimal.valueOf(missing.size()).multiply(bd("4"));
        BigDecimal score = raw.subtract(qualityPenalty).max(BigDecimal.ZERO).min(MAX_SCORE)
                .setScale(3, RoundingMode.HALF_UP);
        return new ScoreResult(score, json("recommendation", snapshot, factors, missing),
                json("risk", snapshot, Map.of("missingFeaturePenalty", qualityPenalty), missing));
    }

    /**
     * 점수 근거를 저장용 JSON 문자열로 만들어요.
     * 받는 것: 종류(recommendation/risk), 스냅샷, 요소별 점수, 빠진 Feature 목록.
     * 돌려주는 것: 전략 버전과 출처(broad_snapshot)가 들어간 JSON 문자열.
     */
    private String json(String kind, MarketBroadSnapshot snapshot, Map<String, BigDecimal> factors,
            List<String> missing) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", ClosingRecommendation.STRATEGY_VERSION);
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

    // 작은 도우미들: cap은 0~상한으로 자르기, value는 null을 0으로, bd는 문자열을 BigDecimal로 바꿔요.
    private BigDecimal cap(BigDecimal value, String max) { return value.max(BigDecimal.ZERO).min(bd(max)); }
    private BigDecimal value(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private BigDecimal bd(String value) { return new BigDecimal(value); }
}
