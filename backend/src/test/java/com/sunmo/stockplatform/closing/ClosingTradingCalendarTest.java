package com.sunmo.stockplatform.closing;

import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClosingTradingCalendarTest {
    @Test
    void skipsVerifiedChuseokClosuresAfterSeptemberTwentyThird() {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 0, 0, false,
                null, null, null, null, null, List.of()));

        assertThat(calendar.nextTradingDay(LocalDate.of(2026, 9, 23)))
                .isEqualTo(LocalDate.of(2026, 9, 28));
    }
}
