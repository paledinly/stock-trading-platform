package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.kis.calendar.KisHolidayClient;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OfficialTradingDaysTest {
    static final LocalDate FRIDAY = LocalDate.of(2026, 10, 2);
    static final LocalDate SUBSTITUTE_HOLIDAY = LocalDate.of(2026, 10, 5);
    static final Instant MORNING = Instant.parse("2026-10-01T22:40:00Z");
    EmbeddedDatabase db;
    JdbcTemplate jdbc;

    @BeforeEach void setup() {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(db);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V31__create_trading_calendar_day.sql")).execute(db);
    }
    @AfterEach void close() { db.shutdown(); }

    ClosingTradingCalendar calendar(OfficialTradingDays days, Instant now, LocalDate... holidays) {
        return new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null, null,
                null, null, List.of(holidays)), Clock.fixed(now, ZoneOffset.UTC), days);
    }

    @Test void providerFlagsWinWhereKnownAndStaticPolicyAppliesElsewhere() {
        var days = new OfficialTradingDays(jdbc);
        days.record(Map.of(SUBSTITUTE_HOLIDAY, false, FRIDAY, true), "KIS_CHK_HOLIDAY", MORNING);
        var calendar = calendar(days, MORNING);

        assertThat(calendar.isStaticTradingDay(SUBSTITUTE_HOLIDAY)).isTrue();
        assertThat(calendar.isTradingDay(SUBSTITUTE_HOLIDAY)).isFalse();
        assertThat(calendar.nextTradingDay(FRIDAY)).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(calendar.isTradingDay(LocalDate.of(2026, 10, 7))).isTrue();
        assertThat(calendar.isTradingDay(LocalDate.of(2026, 10, 9))).isTrue();
        assertThat(calendar(days, MORNING, LocalDate.of(2026, 10, 9)).isTradingDay(LocalDate.of(2026, 10, 9))).isFalse();
    }

    @Test void recordedDaysSurviveRestartAndUpdatesOverwrite() {
        new OfficialTradingDays(jdbc).record(Map.of(SUBSTITUTE_HOLIDAY, true), "KIS_CHK_HOLIDAY", MORNING);
        new OfficialTradingDays(jdbc).record(Map.of(SUBSTITUTE_HOLIDAY, false), "KIS_CHK_HOLIDAY", MORNING.plusSeconds(60));

        var restarted = new OfficialTradingDays(jdbc);

        assertThat(restarted.marketOpen(SUBSTITUTE_HOLIDAY)).contains(false);
        assertThat(restarted.marketOpen(FRIDAY)).isEmpty();
        assertThat(restarted.status().knownDays()).isEqualTo(1);
        assertThat(restarted.status().lastFetchedAt()).isEqualTo(MORNING.plusSeconds(60));
        assertThat(jdbc.queryForObject("select count(*) from trading_calendar_day", Integer.class)).isEqualTo(1);
    }

    @Test void refresherCallsKisAtMostOncePerDayAndKeepsStaticPolicyOnFailure() {
        var days = new OfficialTradingDays(jdbc);
        var calendar = calendar(days, MORNING);
        var client = mock(KisHolidayClient.class);
        when(client.fetch(FRIDAY)).thenThrow(new IllegalStateException("KIS integration is disabled"))
                .thenReturn(new LinkedHashMap<>(Map.of(FRIDAY, true, SUBSTITUTE_HOLIDAY, false)));
        var refresher = new OfficialTradingCalendarRefresher(client, days, calendar);

        assertThat(refresher.refresh()).isFalse();
        assertThat(calendar.isTradingDay(SUBSTITUTE_HOLIDAY)).isTrue();
        assertThat(refresher.refresh()).isTrue();
        assertThat(calendar.isTradingDay(SUBSTITUTE_HOLIDAY)).isFalse();
        assertThat(refresher.refresh()).isFalse();
        verify(client, times(2)).fetch(FRIDAY);
    }
}
