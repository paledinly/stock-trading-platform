package com.sunmo.stockplatform.market.application;

import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.market.config.RealtimeSessionProperties;
import com.sunmo.stockplatform.market.domain.MarketTick;
import org.springframework.stereotype.Component;

@Component
public class RealtimeSessionPolicy {
    private final ClosingTradingCalendar calendar;
    private final RealtimeSessionProperties properties;
    public RealtimeSessionPolicy(ClosingTradingCalendar calendar, RealtimeSessionProperties properties) {
        this.calendar = calendar; this.properties = properties;
    }
    public boolean connectionAllowed() {
        var now = calendar.now();
        var date = now.atZone(ClosingTradingCalendar.ZONE).toLocalDate();
        return calendar.isTradingDay(date)
                && !now.isBefore(calendar.open(date).minus(properties.connectBeforeOpen()))
                && now.isBefore(calendar.close(date).plus(properties.disconnectAfterClose()));
    }
    public boolean accepts(MarketTick tick) {
        var date = tick.businessDate();
        return date.equals(calendar.today()) && calendar.isTradingDay(date)
                && !tick.occurredAt().isBefore(calendar.open(date))
                && !tick.occurredAt().isAfter(calendar.close(date));
    }
}
