package com.sunmo.stockplatform.scanner.score;

import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;
import com.sunmo.stockplatform.scanner.application.ScannerEvaluator;
import org.springframework.stereotype.Component;

import java.math.*;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OpportunityRiskScorer {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public OpportunityRiskScore score(ScannerEvaluator.Metrics metrics, MarketFeatureSnapshot feature) {
        Map<String, BigDecimal> opportunity = new LinkedHashMap<>();
        Map<String, BigDecimal> risk = new LinkedHashMap<>();

        opportunity.put("priceMomentum", cap(mult(positive(metrics.changeRate()), "12"), "30"));
        opportunity.put("volumeExpansion", cap(mult(positive(sub(metrics.volumeRatio(), BigDecimal.ONE)), "15"), "25"));
        opportunity.put("vwapLeadership",
                cap(mult(positive(value(feature == null ? null : feature.vwapDistanceRate())), "8"), "20"));
        opportunity.put("tradeStrength", cap(
                mult(positive(sub(value(feature == null ? null : feature.tradeStrength()), HUNDRED)), "0.25"), "15"));
        opportunity.put("dayHighProximity", dayHighProximity(feature));

        risk.put("vwapOverextension", cap(
                mult(positive(sub(value(feature == null ? null : feature.vwapDistanceRate()), bd("3"))), "8"), "25"));
        risk.put("weakTradeStrength", cap(
                mult(positive(sub(HUNDRED, value(feature == null ? null : feature.tradeStrength()))), "0.25"), "20"));
        risk.put("negativeMomentum",
                cap(mult(positive(metrics.changeRate() == null ? null : metrics.changeRate().negate()), "10"), "20"));
        risk.put("farFromDayHigh",
                cap(mult(positive(sub(value(feature == null ? null : feature.dayHighDistanceRate()), bd("2"))), "5"),
                        "15"));
        risk.put("sellPressure", sellPressure(feature));

        return new OpportunityRiskScore(total(opportunity), total(risk), Map.copyOf(opportunity), Map.copyOf(risk),
                OpportunityRiskScore.VERSION);
    }

    private BigDecimal dayHighProximity(MarketFeatureSnapshot feature) {
        BigDecimal distance = value(feature == null ? null : feature.dayHighDistanceRate());
        BigDecimal score = bd("10").subtract(distance.multiply(bd("5")));
        return cap(positive(score), "10");
    }

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

    private BigDecimal total(Map<String, BigDecimal> factors) {
        BigDecimal total = factors.values().stream().reduce(ZERO, BigDecimal::add);
        return cap(total, "100").setScale(3, RoundingMode.HALF_UP);
    }

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
