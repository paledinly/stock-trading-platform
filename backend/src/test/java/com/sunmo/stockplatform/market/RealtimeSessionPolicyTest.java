package com.sunmo.stockplatform.market;

import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.market.application.RealtimeSessionPolicy;
import com.sunmo.stockplatform.market.config.*;
import com.sunmo.stockplatform.market.domain.MarketTick;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class RealtimeSessionPolicyTest {
    private RealtimeSessionPolicy at(String time) {
        var schedule = new MarketWideScheduleProperties(false, 0, 0, false, null, null, null, null, null,
                List.of(LocalDate.of(2026, 10, 9)));
        var clock = Clock.fixed(LocalDateTime.parse(time).atZone(ClosingTradingCalendar.ZONE).toInstant(), ZoneOffset.UTC);
        return new RealtimeSessionPolicy(new ClosingTradingCalendar(schedule, clock),
                new RealtimeSessionProperties(Duration.ofMinutes(1), Duration.ofMinutes(1)));
    }
    @Test void connectsOnlyInTradingDayWindowIncludingClosingAuctionAndReceiptGrace() {
        assertThat(at("2026-10-01T08:58:59").connectionAllowed()).isFalse();
        assertThat(at("2026-10-01T08:59:00").connectionAllowed()).isTrue();
        assertThat(at("2026-10-01T15:25:00").connectionAllowed()).isTrue();
        assertThat(at("2026-10-01T15:30:59").connectionAllowed()).isTrue();
        assertThat(at("2026-10-01T15:31:00").connectionAllowed()).isFalse();
        assertThat(at("2026-10-03T10:00:00").connectionAllowed()).isFalse();
        assertThat(at("2026-10-09T10:00:00").connectionAllowed()).isFalse();
        assertThat(at("2026-09-24T10:00:00").connectionAllowed()).isFalse();
    }
    @Test void acceptsFinalClosingPrintButNotPremarketAfterHoursOrPreviousDay() {
        var policy = at("2026-10-01T15:30:20");
        assertThat(policy.accepts(tick("2026-10-01T15:30:00"))).isTrue();
        assertThat(policy.accepts(tick("2026-10-01T15:30:01"))).isFalse();
        assertThat(policy.accepts(tick("2026-10-01T08:59:59"))).isFalse();
        assertThat(policy.accepts(tick("2026-09-30T15:30:00"))).isFalse();
    }
    private MarketTick tick(String local) {
        var time = LocalDateTime.parse(local);
        return new MarketTick("005930", time.toLocalDate(), time.atZone(ClosingTradingCalendar.ZONE).toInstant(),
                BigDecimal.TEN, 1, 1, BigDecimal.TEN, 1);
    }
}
