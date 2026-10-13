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
        Double partialAtPercent, Double partialFraction, Double trailingDistancePercent,
        boolean krxTickSizes, Integer minimumStopTicks) {
    public IntradayExperimentOptions {
        tickSizes = tickSizes == null ? Map.of() : Map.copyOf(tickSizes);
        minimumStopPercent = minimumStopPercent == null ? 0 : minimumStopPercent;
        narrowStopMode = narrowStopMode == null ? "WIDEN" : narrowStopMode;
        entryMode = entryMode == null ? "IMMEDIATE" : entryMode;
        holdSeconds = holdSeconds == null ? 0 : holdSeconds;
        maxStoppedPerSymbol = maxStoppedPerSymbol == null ? 0 : maxStoppedPerSymbol;
        minimumStopTicks = minimumStopTicks == null ? 0 : minimumStopTicks;
        if (!Set.of("WIDEN", "FILTER").contains(narrowStopMode)
                || !Set.of("IMMEDIATE", "HOLD", "RETEST").contains(entryMode)
                || holdSeconds < 0 || holdSeconds > 3600 || entryMode.equals("HOLD") && holdSeconds == 0
                || maxStoppedPerSymbol < 0 || maxStoppedPerSymbol > 100
                || minimumStopTicks < 0 || minimumStopTicks > 100
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
        return new IntradayExperimentOptions(null, null, null, null, null, null, null, false, null, null, null, false, null);
    }
    public boolean changesEntry() {
        return !entryMode.equals("IMMEDIATE") || maxStoppedPerSymbol > 0 || requireFreshStructureAfterStop
                || minimumStopActive() && narrowStopMode.equals("FILTER");
    }
    public boolean minimumStopActive() { return minimumStopPercent > 0 || minimumStopTicks > 0; }
    public boolean gridEnabled() { return !tickSizes.isEmpty() || krxTickSizes; }
    /** Explicit per-symbol step wins; otherwise the KRX table when enabled. NaN means the grid is unknown. */
    public double tick(String symbol, double price) {
        Double step = tickSizes.get(symbol);
        if (step != null) return step;
        return krxTickSizes ? KrxTickSize.at(price) : Double.NaN;
    }
    public double round(String symbol, double price, boolean up) {
        if (!gridEnabled()) return price;
        Double step = tickSizes.get(symbol);
        if (step == null) return krxTickSizes ? KrxTickSize.round(price, up) : Double.NaN;
        return BigDecimal.valueOf(price).divide(BigDecimal.valueOf(step), 0, up ? RoundingMode.CEILING : RoundingMode.FLOOR)
                .multiply(BigDecimal.valueOf(step)).doubleValue();
    }
    /** Larger of the percent and tick floors below entry; NaN when a tick floor is asked for without a grid. */
    public double minimumStopDistance(String symbol, double entry) {
        double byPercent = entry * minimumStopPercent / 100;
        if (minimumStopTicks == 0) return byPercent;
        double tick = tick(symbol, entry);
        return Double.isNaN(tick) ? Double.NaN : Math.max(byPercent, minimumStopTicks * tick);
    }
}
