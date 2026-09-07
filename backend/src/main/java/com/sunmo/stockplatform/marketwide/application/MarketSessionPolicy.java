package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.springframework.stereotype.Component;
import java.time.*;

@Component
public class MarketSessionPolicy {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private final MarketWideScheduleProperties properties;

    public MarketSessionPolicy(MarketWideScheduleProperties properties) {
        this.properties = properties;
    }

    public Decision evaluate(Instant now) {
        ZonedDateTime marketNow = now.atZone(MARKET_ZONE);
        LocalDate date = marketNow.toLocalDate();
        DayOfWeek day = marketNow.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY)
            return new Decision(false, null, "WEEKEND");
        if (properties.holidays().contains(date))
            return new Decision(false, null, "CONFIGURED_HOLIDAY");
        LocalTime time = marketNow.toLocalTime();
        if (time.isBefore(properties.open()) || time.isAfter(properties.close()))
            return new Decision(false, null, "OUTSIDE_SESSION");
        Duration interval = time.isBefore(properties.lateStart())
                ? properties.normalInterval() : properties.lateInterval();
        long intervalSeconds = Math.max(60, interval.toSeconds());
        long elapsed = Duration.between(date.atStartOfDay(), marketNow.toLocalDateTime()).toSeconds();
        long bucketElapsed = Math.floorDiv(elapsed, intervalSeconds) * intervalSeconds;
        Instant bucket = date.atStartOfDay(MARKET_ZONE).plusSeconds(bucketElapsed).toInstant();
        return new Decision(true, bucket, "ELIGIBLE");
    }

    public record Decision(boolean eligible, Instant bucket, String reason) {
    }
}
