package com.sunmo.stockplatform.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.*;
import java.util.List;

@ConfigurationProperties(prefix = "market.wide.schedule")
public record MarketWideScheduleProperties(boolean enabled, int scanLimit, int candidateLimit, boolean includeEtf,
        LocalTime open, LocalTime close, LocalTime lateStart, Duration normalInterval, Duration lateInterval,
        List<LocalDate> holidays) {
    public MarketWideScheduleProperties {
        scanLimit = scanLimit <= 0 ? 120 : Math.min(scanLimit, 120);
        candidateLimit = candidateLimit <= 0 ? 30 : Math.min(candidateLimit, 30);
        open = open == null ? LocalTime.of(9, 0) : open;
        close = close == null ? LocalTime.of(15, 20) : close;
        lateStart = lateStart == null ? LocalTime.of(14, 0) : lateStart;
        normalInterval = normalInterval == null ? Duration.ofMinutes(5) : normalInterval;
        lateInterval = lateInterval == null ? Duration.ofMinutes(2) : lateInterval;
        holidays = holidays == null ? List.of() : List.copyOf(holidays);
    }
}
