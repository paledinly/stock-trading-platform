/*
 * 감지 순간의 지표와 특징값으로 기회 점수와 위험 점수를 따로 계산하는 파일이에요.
 * 항목마다 배율을 곱하고 상한을 씌운 뒤 더해서 0~100 범위로 맞춰요.
 * ScannerEngine이 감지 기록에 점수를 붙일 때 써요.
 */
package com.sunmo.stockplatform.scanner.score;

import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;
import com.sunmo.stockplatform.scanner.application.ScannerEvaluator;
import org.springframework.stereotype.Component;

import java.math.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 기회/위험 점수를 매기는 채점기 클래스예요.
 * 요리 대회 심사위원이 맛·모양 같은 항목별로 점수를 매기되 항목마다 최고점을 정해 두는 것처럼, 항목별 상한을 두고 합산해요.
 */
@Component
public class OpportunityRiskScorer {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * 하는 일: 기회 항목 5개와 위험 항목 5개를 계산해 각각 합쳐요.
     * 받는 것: metrics(변화율·거래량 배수), feature(VWAP 거리·체결강도·고가 거리·매수/매도 체결량, 없으면 null).
     * 돌려주는 것: OpportunityRiskScore(총점 2개, 항목별 점수, 버전).
     */
    public OpportunityRiskScore score(ScannerEvaluator.Metrics metrics, MarketFeatureSnapshot feature) {
        Map<String, BigDecimal> opportunity = new LinkedHashMap<>();
        Map<String, BigDecimal> risk = new LinkedHashMap<>();

        // 기회: 가격 상승, 거래량 확대, VWAP 위 거리, 체결강도 100 초과분, 당일 고가 근접도예요.
        opportunity.put("priceMomentum", cap(mult(positive(metrics.changeRate()), "12"), "30"));
        opportunity.put("volumeExpansion", cap(mult(positive(sub(metrics.volumeRatio(), BigDecimal.ONE)), "15"), "25"));
        opportunity.put("vwapLeadership",
                cap(mult(positive(value(feature == null ? null : feature.vwapDistanceRate())), "8"), "20"));
        opportunity.put("tradeStrength", cap(
                mult(positive(sub(value(feature == null ? null : feature.tradeStrength()), HUNDRED)), "0.25"), "15"));
        opportunity.put("dayHighProximity", dayHighProximity(feature));

        // 위험: VWAP 과열(3% 초과), 체결강도 100 미만, 하락 모멘텀, 고가에서 2% 넘게 멂, 마지막 틱 매도 우위예요.
        // ⚠️ 확인 필요: feature가 없으면 체결강도를 0으로 봐서 weakTradeStrength가 최대 20점이 돼요. 특징값 누락이 위험 점수를 올려요.
        risk.put("vwapOverextension", cap(
                mult(positive(sub(value(feature == null ? null : feature.vwapDistanceRate()), bd("3"))), "8"), "25"));
        risk.put("weakTradeStrength", cap(
                mult(positive(sub(HUNDRED, value(feature == null ? null : feature.tradeStrength()))), "0.25"), "20"));
        risk.put("negativeMomentum",
                cap(mult(positive(metrics.changeRate() == null ? null : metrics.changeRate().negate()), "10"), "20"));
        risk.put("farFromDayHigh",
                cap(mult(positive(sub(value(feature == null ? null : feature.dayHighDistanceRate()), bd("2"))), "5"),
                        "15"));
        risk.put("lastTickSellImbalance", sellPressure(feature));

        return new OpportunityRiskScore(total(opportunity), total(risk), Map.copyOf(opportunity), Map.copyOf(risk),
                OpportunityRiskScore.VERSION);
    }

    /**
     * 하는 일: 당일 고가에 가까울수록 높은 점수(최대 10점)를 줘요. 거리 1%마다 5점씩 깎여요.
     * 받는 것: feature.
     * 돌려주는 것: 0~10 점수.
     * ⚠️ 확인 필요: feature가 없으면 거리를 0으로 봐서 만점 10점을 줘요. 데이터가 없을 때 기회 점수가 오히려 올라가요.
     */
    private BigDecimal dayHighProximity(MarketFeatureSnapshot feature) {
        BigDecimal distance = value(feature == null ? null : feature.dayHighDistanceRate());
        BigDecimal score = bd("10").subtract(distance.multiply(bd("5")));
        return cap(positive(score), "10");
    }

    /**
     * 하는 일: 마지막 틱 구간에서 매도 체결량이 매수보다 많으면 '매도 비중 x 20'점(최대 20점)을 위험으로 매겨요.
     * 받는 것: feature.
     * 돌려주는 것: 0~20 점수. 특징값이 없거나 매수 우위면 0이에요.
     */
    private BigDecimal sellPressure(MarketFeatureSnapshot feature) {
        if (feature == null)
            return ZERO;
        long total = feature.buyVolumeDelta() + feature.sellVolumeDelta();
        if (total <= 0 || feature.sellVolumeDelta() <= feature.buyVolumeDelta())
            return ZERO;
        BigDecimal sellShare = BigDecimal.valueOf(feature.sellVolumeDelta())
                .divide(BigDecimal.valueOf(total), 8, RoundingMode.HALF_UP);
        return cap(sellShare.multiply(bd("20")), "20");
    }

    /**
     * 하는 일: 항목 점수를 모두 더하고 100을 넘지 않게 자른 뒤 소수 3자리로 맞춰요.
     * 받는 것: factors(항목별 점수).
     * 돌려주는 것: 총점.
     */
    private BigDecimal total(Map<String, BigDecimal> factors) {
        BigDecimal total = factors.values().stream().reduce(ZERO, BigDecimal::add);
        return cap(total, "100").setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * 아래는 작은 계산 도우미들이에요.
     * value: null을 0으로, sub: 둘 중 하나라도 없으면 null, positive: 음수/null을 0으로, mult: 문자열 배율 곱하기,
     * cap: 0~상한으로 자르고 소수 6자리로 맞추기, bd: 문자열을 BigDecimal(정확한 소수 숫자)로 바꾸기.
     */
    private BigDecimal value(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private BigDecimal sub(BigDecimal left, BigDecimal right) {
        if (left == null || right == null)
            return null;
        return left.subtract(right);
    }

    private BigDecimal positive(BigDecimal value) {
        return value == null || value.signum() < 0 ? ZERO : value;
    }

    private BigDecimal mult(BigDecimal value, String multiplier) {
        return value.multiply(bd(multiplier));
    }

    private BigDecimal cap(BigDecimal value, String max) {
        BigDecimal upper = bd(max);
        if (value.compareTo(ZERO) < 0)
            return ZERO;
        return value.compareTo(upper) > 0 ? upper : value.setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
