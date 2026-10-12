package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.Optional;
import java.util.Set;

@Component
public class ClosingTradingCalendar {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    // KRX closures confirmed for the audited period. Additional closures remain configurable.
    private static final Set<LocalDate> VERIFIED_SPECIAL_CLOSURES = Set.of(
            LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25));
    private final MarketWideScheduleProperties schedule;
    private final Clock clock;
    private final OfficialTradingDays official;

    @Autowired
    public ClosingTradingCalendar(MarketWideScheduleProperties schedule, OfficialTradingDays official) {
        this(schedule, Clock.systemUTC(), official);
    }

    public ClosingTradingCalendar(MarketWideScheduleProperties schedule) {
        this(schedule, Clock.systemUTC());
    }

    public ClosingTradingCalendar(MarketWideScheduleProperties schedule, Clock clock) {
        this(schedule, clock, null);
    }

    public ClosingTradingCalendar(MarketWideScheduleProperties schedule, Clock clock, OfficialTradingDays official) {
        this.schedule = schedule;
        this.clock = clock;
        this.official = official;
    }

    public Instant now() { return clock.instant(); }
    public LocalDate today() { return now().atZone(ZONE).toLocalDate(); }

    /** Provider open flags win where fetched; otherwise weekends, verified closures and configured holidays. */
    public boolean isTradingDay(LocalDate date) {
        if (official != null) {
            Optional<Boolean> open = official.marketOpen(date);
            if (open.isPresent()) return open.get();
        }
        return isStaticTradingDay(date);
    }

    public boolean isStaticTradingDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY
                && !VERIFIED_SPECIAL_CLOSURES.contains(date) && !schedule.holidays().contains(date);
    }

    public LocalDate nextTradingDay(LocalDate date) {
        LocalDate next = date.plusDays(1);
        while (!isTradingDay(next)) next = next.plusDays(1);
        return next;
    }

    public LocalDate previousTradingDay(LocalDate date) {
        LocalDate previous = date.minusDays(1);
        while (!isTradingDay(previous)) previous = previous.minusDays(1);
        return previous;
    }

    // Regular-session hours are independent of the market-wide scanner's 15:20 cutoff.
    public Instant open(LocalDate date) { return date.atTime(9, 0).atZone(ZONE).toInstant(); }
    public Instant close(LocalDate date) { return date.atTime(15, 30).atZone(ZONE).toInstant(); }
}
