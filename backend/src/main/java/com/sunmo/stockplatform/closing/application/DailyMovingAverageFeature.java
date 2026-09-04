package com.sunmo.stockplatform.closing.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

public record DailyMovingAverageFeature(
        boolean ready,
        int candleCount,
        LocalDate asOfDate,
        BigDecimal lastClose,
        BigDecimal ma5,
        BigDecimal ma20,
        BigDecimal ma60,
        BigDecimal ma5DistanceRate,
        BigDecimal ma20DistanceRate,
        BigDecimal ma60DistanceRate,
        BigDecimal ma20SlopeRate,
        boolean closeAboveMa20,
        boolean ma5AboveMa20,
        boolean ma20Rising,
        boolean bullishAlignment,
        boolean overextendedFromMa20) {
    public static DailyMovingAverageFeature empty(int candleCount) {
        return new DailyMovingAverageFeature(false, candleCount, null, null, null, null, null, null, null, null, null,
                false, false, false, false, false);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ready", ready);
        values.put("candleCount", candleCount);
        values.put("asOfDate", asOfDate == null ? "" : asOfDate.toString());
        values.put("lastClose", plain(lastClose));
        values.put("ma5", plain(ma5));
        values.put("ma20", plain(ma20));
        values.put("ma60", plain(ma60));
        values.put("ma5DistanceRate", plain(ma5DistanceRate));
        values.put("ma20DistanceRate", plain(ma20DistanceRate));
        values.put("ma60DistanceRate", plain(ma60DistanceRate));
        values.put("ma20SlopeRate", plain(ma20SlopeRate));
        values.put("closeAboveMa20", closeAboveMa20);
        values.put("ma5AboveMa20", ma5AboveMa20);
        values.put("ma20Rising", ma20Rising);
        values.put("bullishAlignment", bullishAlignment);
        values.put("overextendedFromMa20", overextendedFromMa20);
        return values;
    }

    private String plain(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }
}
