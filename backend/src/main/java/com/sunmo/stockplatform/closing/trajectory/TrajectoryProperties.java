package com.sunmo.stockplatform.closing.trajectory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.math.BigDecimal;
import java.time.*;

@ConfigurationProperties("closing.trajectory")
public record TrajectoryProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean indexEnabled,
        @DefaultValue("14:30") LocalTime analysisStart,
        @DefaultValue("15:20") LocalTime analysisEnd,
        @DefaultValue("5") int snapshotMinutes,
        @DefaultValue("2s") Duration watermark,
        @DefaultValue("6m") Duration contextMaxAge,
        @DefaultValue("1") BigDecimal pullbackPercent,
        @DefaultValue("10:30") LocalTime morningEnd,
        @DefaultValue("3") BigDecimal targetPercent,
        @DefaultValue("-2") BigDecimal stopPercent,
        @DefaultValue({"5", "15", "30"}) java.util.List<Integer> volumeComparisonWindows,
        @DefaultValue({"5", "15", "30"}) java.util.List<Integer> turnoverComparisonWindows) {
    public TrajectoryProperties {
        volumeComparisonWindows = java.util.List.copyOf(volumeComparisonWindows);
        turnoverComparisonWindows = java.util.List.copyOf(turnoverComparisonWindows);
        if (snapshotMinutes < 1 || snapshotMinutes > 30 || watermark.isNegative()
                || contextMaxAge.isNegative() || contextMaxAge.isZero()
                || analysisStart.isBefore(LocalTime.of(9, 0)) || !analysisStart.isBefore(analysisEnd)
                || analysisEnd.isAfter(LocalTime.of(15, 20))
                || !morningEnd.isAfter(LocalTime.of(9, 0)) || morningEnd.isAfter(LocalTime.NOON)
                || morningEnd.getMinute() % 5 != 0 || morningEnd.getSecond() != 0
                || pullbackPercent.signum() <= 0 || targetPercent.signum() <= 0
                || stopPercent.signum() >= 0 || stopPercent.compareTo(BigDecimal.valueOf(-100)) <= 0)
            throw new IllegalArgumentException("Invalid closing trajectory policy");
        if (volumeComparisonWindows.isEmpty() || turnoverComparisonWindows.isEmpty()
                || volumeComparisonWindows.stream().anyMatch(n -> n < 1 || n > 60)
                || turnoverComparisonWindows.stream().anyMatch(n -> n < 1 || n > 60))
            throw new IllegalArgumentException("Comparison windows must be between 1 and 60 minutes");
    }
}
