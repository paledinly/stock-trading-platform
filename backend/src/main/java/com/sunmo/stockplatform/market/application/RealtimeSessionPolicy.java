package com.sunmo.stockplatform.market.application;

import com.sunmo.stockplatform.candle.application.FiveMinuteCandleAggregator;
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
    /** Regular session 09:00..15:30 plus the random-end closing auction print (source time up to 15:31:00). */
    public boolean accepts(MarketTick tick) {
        var date = tick.businessDate();
        return date.equals(calendar.today()) && calendar.isTradingDay(date)
                && !tick.occurredAt().isBefore(calendar.open(date))
                && !tick.occurredAt().isAfter(closingAuctionDeadline(date));
    }
    /** True for an accepted tick stamped after 15:30:00: the closing single-price execution. */
    public boolean closingAuctionPrint(MarketTick tick) {
        return tick.occurredAt().isAfter(calendar.close(tick.businessDate()))
                && !tick.occurredAt().isAfter(closingAuctionDeadline(tick.businessDate()));
    }
    private java.time.Instant closingAuctionDeadline(java.time.LocalDate date) {
        return calendar.close(date).plus(FiveMinuteCandleAggregator.CLOSING_AUCTION_WINDOW);
    }
}
