package com.sunmo.stockplatform.closing.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public record IntradayMovingAverageFeature(
        boolean ready,
        int candleCount,
        BigDecimal lastClose,
        BigDecimal ma5,
        BigDecimal ma20,
        BigDecimal ma60,
        BigDecimal ma5DistanceRate,
        BigDecimal ma20DistanceRate,
        BigDecimal ma60DistanceRate,
        boolean bullishAlignment,
        boolean goldenCross,
        boolean ma20Support,
        boolean ma20Broken) {
    public static IntradayMovingAverageFeature empty(int candleCount) {
        return new IntradayMovingAverageFeature(false, candleCount, null, null, null, null, null, null, null,
                false, false, false, false);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ready", ready);
        values.put("candleCount", candleCount);
        values.put("lastClose", plain(lastClose));
        values.put("ma5", plain(ma5));
        values.put("ma20", plain(ma20));
        values.put("ma60", plain(ma60));
        values.put("ma5DistanceRate", plain(ma5DistanceRate));
        values.put("ma20DistanceRate", plain(ma20DistanceRate));
        values.put("ma60DistanceRate", plain(ma60DistanceRate));
        values.put("bullishAlignment", bullishAlignment);
        values.put("goldenCross", goldenCross);
        values.put("ma20Support", ma20Support);
        values.put("ma20Broken", ma20Broken);
        return values;
    }

    private String plain(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }
}
