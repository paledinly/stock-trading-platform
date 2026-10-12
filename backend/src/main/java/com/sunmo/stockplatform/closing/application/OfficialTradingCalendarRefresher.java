package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.kis.calendar.KisHolidayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/** Fetches KIS market-open flags at most once per KST date (startup or morning cron). */
@Component
@ConditionalOnProperty(prefix = "closing.calendar", name = "kis-refresh-enabled", havingValue = "true")
public class OfficialTradingCalendarRefresher {
    private static final Logger log = LoggerFactory.getLogger(OfficialTradingCalendarRefresher.class);
    private final KisHolidayClient client;
    private final OfficialTradingDays days;
    private final ClosingTradingCalendar calendar;

    public OfficialTradingCalendarRefresher(KisHolidayClient client, OfficialTradingDays days, ClosingTradingCalendar calendar) {
        this.client = client;
        this.days = days;
        this.calendar = calendar;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() { refresh(); }

    @Scheduled(cron = "${closing.calendar.kis-refresh-cron:0 40 7 * * *}", zone = "Asia/Seoul")
    public void scheduled() { refresh(); }

    public synchronized boolean refresh() {
        LocalDate today = calendar.today();
        Instant last = days.status().lastFetchedAt();
        if (last != null && !last.atZone(ClosingTradingCalendar.ZONE).toLocalDate().isBefore(today))
            return false;
        try {
            Map<LocalDate, Boolean> fetched = client.fetch(today);
            if (fetched.isEmpty()) {
                log.warn("KIS holiday response had no usable dates for {}; static calendar policy remains", today);
                return false;
            }
            fetched.forEach((date, open) -> {
                if (open != calendar.isStaticTradingDay(date))
                    log.info("KIS market-open flag {} for {} differs from static calendar; provider value applies", open, date);
            });
            days.record(fetched, "KIS_CHK_HOLIDAY", calendar.now());
            return true;
        } catch (RuntimeException error) {
            log.warn("KIS holiday refresh failed; static calendar policy remains: {}", error.getMessage());
            return false;
        }
    }
}
