package com.sunmo.stockplatform.intraday;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;

/** Explicit research assumptions. None of these settings changes live recommendation policy. */
public record IntradayExperimentOptions(
        Map<String, Double> tickSizes, Double minimumStopPercent, String narrowStopMode,
        String entryMode, Integer holdSeconds, Double retestTolerancePercent,
        Integer maxStoppedPerSymbol, boolean requireFreshStructureAfterStop,
        Double partialAtPercent, Double partialFraction, Double trailingDistancePercent) {
    public IntradayExperimentOptions {
        tickSizes = tickSizes == null ? Map.of() : Map.copyOf(tickSizes);
        minimumStopPercent = minimumStopPercent == null ? 0 : minimumStopPercent;
        narrowStopMode = narrowStopMode == null ? "WIDEN" : narrowStopMode;
        entryMode = entryMode == null ? "IMMEDIATE" : entryMode;
        holdSeconds = holdSeconds == null ? 0 : holdSeconds;
        maxStoppedPerSymbol = maxStoppedPerSymbol == null ? 0 : maxStoppedPerSymbol;
        if (!Set.of("WIDEN", "FILTER").contains(narrowStopMode)
                || !Set.of("IMMEDIATE", "HOLD", "RETEST").contains(entryMode)
                || holdSeconds < 0 || holdSeconds > 3600 || entryMode.equals("HOLD") && holdSeconds == 0
                || maxStoppedPerSymbol < 0 || maxStoppedPerSymbol > 100
                || !Double.isFinite(minimumStopPercent) || minimumStopPercent < 0 || minimumStopPercent >= 100)
            throw new IllegalArgumentException("Invalid experiment options");
        tickSizes.forEach((symbol, step) -> {
            if (!symbol.matches("[A-Za-z0-9]{6}") || !Double.isFinite(step) || step <= 0)
                throw new IllegalArgumentException("Invalid explicit price grid");
        });
        for (Double value : new Double[]{retestTolerancePercent, partialAtPercent, partialFraction, trailingDistancePercent})
            if (value != null && (!Double.isFinite(value) || value <= 0 || value >= 100))
                throw new IllegalArgumentException("Invalid experiment percentage");
        if (entryMode.equals("RETEST") && retestTolerancePercent == null
                || (partialAtPercent == null) != (partialFraction == null)
                || partialFraction != null && partialFraction >= 1)
            throw new IllegalArgumentException("Incomplete experiment options");
    }
    public static IntradayExperimentOptions none() {
        return new IntradayExperimentOptions(null, null, null, null, null, null, null, false, null, null, null);
    }
    public boolean changesEntry() {
        return !entryMode.equals("IMMEDIATE") || maxStoppedPerSymbol > 0 || requireFreshStructureAfterStop
                || minimumStopPercent > 0 && narrowStopMode.equals("FILTER");
    }
    public double round(String symbol, double price, boolean up) {
        if (tickSizes.isEmpty()) return price;
        Double step = tickSizes.get(symbol);
        if (step == null) return Double.NaN;
        return BigDecimal.valueOf(price).divide(BigDecimal.valueOf(step), 0, up ? RoundingMode.CEILING : RoundingMode.FLOOR)
                .multiply(BigDecimal.valueOf(step)).doubleValue();
    }
}
