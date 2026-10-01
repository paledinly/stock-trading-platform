package com.sunmo.stockplatform.intraday;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;
import java.util.Map;

/** Research parameters, deliberately disabled until a comparison has been reviewed. Percent units throughout. */
@ConfigurationProperties("intraday")
public record IntradayProperties(
        boolean enabled,
        @DefaultValue("intraday-research-v2-optional-costs") String version,
        @DefaultValue("20000") int queueCapacity,
        @DefaultValue("1000") int batchSize,
        @DefaultValue("100000") int maxHistoryTicksPerStock,
        @DefaultValue("10s") Duration maxDelay,
        @DefaultValue("60s") Duration maxGap,
        @DefaultValue("20m") Duration cooldown,
        @DefaultValue("60m") Duration maxHolding,
        @DefaultValue("1000000000") double minDailyValue,
        @DefaultValue("100000000") double minFiveMinuteValue,
        @DefaultValue("1.5") double minVolumeRatio,
        @DefaultValue("1.2") double minValueRatio,
        @DefaultValue("3") double maxVwapDistance,
        @DefaultValue("5") double maxFiveMinuteReturn,
        @DefaultValue("6") double maxVolumeRatio,
        @DefaultValue("0.5") double maxUpperWick,
        @DefaultValue("1.5") double minRiskReward,
        @DefaultValue("0.25") double entryRangeFraction,
        @DefaultValue("0.1") double stopBufferFraction,
        @DefaultValue("2") double targetRiskMultiple,
        @DefaultValue("RISK_MULTIPLE") String targetMode,
        @DefaultValue("false") boolean trailingAfterTarget,
        @DefaultValue("0.7") double pullbackVolumeFraction,
        @DefaultValue("10") double maxStrengthDrop,
        @DefaultValue("false") boolean requireCompleteRiskData,
        Map<String, Integer> expiryMinutes,
        Map<String, Double> sessionVolumeMultipliers,
        @DefaultValue Costs costs) {
    public IntradayProperties {
        costs = costs == null ? new Costs(null, null, null, null, null, null) : costs;
        if (maxDelay == null || maxGap == null || cooldown == null || maxHolding == null)
            throw new IllegalArgumentException("Complete time policy is required");
        expiryMinutes = expiryMinutes == null || expiryMinutes.isEmpty()
                ? Map.of("BREAKOUT", 5, "PULLBACK", 15, "RE_BREAKOUT", 10) : Map.copyOf(expiryMinutes);
        sessionVolumeMultipliers = sessionVolumeMultipliers == null || sessionVolumeMultipliers.isEmpty()
                ? Map.of("OPENING", 1.2, "MID_SESSION", 1.0, "LATE_SESSION", 1.1)
                : Map.copyOf(sessionVolumeMultipliers);
        for (String setup : new String[]{"BREAKOUT", "PULLBACK", "RE_BREAKOUT"})
            if (!expiryMinutes.containsKey(setup) || expiryMinutes.get(setup) < 1 || expiryMinutes.get(setup) > 60)
                throw new IllegalArgumentException("All setup expirations must be between 1 and 60 minutes");
        for (String session : new String[]{"OPENING", "MID_SESSION", "LATE_SESSION"})
            if (!sessionVolumeMultipliers.containsKey(session) || !Double.isFinite(sessionVolumeMultipliers.get(session))
                    || sessionVolumeMultipliers.get(session) <= 0)
                throw new IllegalArgumentException("All session multipliers must be positive");
        if (version == null || version.isBlank() || maxDelay.isNegative() || maxDelay.isZero()
                || maxGap.isNegative() || maxGap.isZero() || cooldown.isNegative()
                || maxHolding.isNegative() || maxHolding.isZero() || maxHolding.compareTo(Duration.ofHours(6)) > 0)
            throw new IllegalArgumentException("Invalid intraday time policy");
        if (queueCapacity < 1 || queueCapacity > 1000000 || batchSize < 1 || batchSize > queueCapacity
                || maxHistoryTicksPerStock < 60 || maxHistoryTicksPerStock > 1000000)
            throw new IllegalArgumentException("Invalid intraday buffer bounds");
        for (double value : new double[]{minDailyValue, minFiveMinuteValue, minVolumeRatio, minValueRatio,
                maxVwapDistance, maxFiveMinuteReturn, maxVolumeRatio, maxUpperWick, minRiskReward,
                entryRangeFraction, stopBufferFraction, targetRiskMultiple, pullbackVolumeFraction, maxStrengthDrop})
            if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("Invalid research threshold");
        if (maxUpperWick > 1 || pullbackVolumeFraction >= 1 || entryRangeFraction > 1)
            throw new IllegalArgumentException("Invalid range fraction");
        if (targetMode == null || !java.util.Set.of("RISK_MULTIPLE", "FIXED_1", "FIXED_2", "FIXED_3", "PRIOR_HIGH", "RANGE").contains(targetMode))
            throw new IllegalArgumentException("Unknown target mode");
    }

    public record Costs(Double buyFeePercent, Double sellFeePercent, Double sellTaxPercent,
                        Double spreadPercent, Double buySlippagePercent, Double sellSlippagePercent) {
        public Costs {
            for (Double value : new Double[]{buyFeePercent, sellFeePercent, sellTaxPercent, spreadPercent,
                    buySlippagePercent, sellSlippagePercent})
                if (value != null && (!Double.isFinite(value) || value < 0 || value >= 20))
                    throw new IllegalArgumentException("Invalid cost percentage");
        }
        public boolean configured() {
            return buyFeePercent != null && sellFeePercent != null && sellTaxPercent != null
                    && spreadPercent != null && buySlippagePercent != null && sellSlippagePercent != null;
        }
        public double buy(double price) { return price * (1 + (spreadPercent / 2 + buySlippagePercent) / 100); }
        public double sell(double price) { return price * (1 - (spreadPercent / 2 + sellSlippagePercent) / 100); }
        public double net(double entryFill, double exitFill) {
            return (exitFill * (1 - (sellFeePercent + sellTaxPercent) / 100)
                    / (entryFill * (1 + buyFeePercent / 100)) - 1) * 100;
        }
    }
}
