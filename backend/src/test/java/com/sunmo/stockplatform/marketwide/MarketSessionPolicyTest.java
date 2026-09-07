package com.sunmo.stockplatform.marketwide;

import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import com.sunmo.stockplatform.marketwide.application.MarketSessionPolicy;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MarketSessionPolicyTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    void usesFiveMinuteBucketsBeforeLateSessionAndTwoMinutesAfterward() {
        MarketSessionPolicy policy = policy(List.of());
        var normal = policy.evaluate(at("2026-09-07T13:57:45"));
        var late = policy.evaluate(at("2026-09-07T14:07:45"));

        assertThat(normal.eligible()).isTrue();
        assertThat(normal.bucket()).isEqualTo(at("2026-09-07T13:55:00"));
        assertThat(late.bucket()).isEqualTo(at("2026-09-07T14:06:00"));
    }

    @Test
    void rejectsWeekendConfiguredHolidayAndOutsideSession() {
        MarketSessionPolicy policy = policy(List.of(LocalDate.of(2026, 9, 8)));
        assertThat(policy.evaluate(at("2026-09-06T10:00:00")).reason()).isEqualTo("WEEKEND");
        assertThat(policy.evaluate(at("2026-09-08T10:00:00")).reason()).isEqualTo("CONFIGURED_HOLIDAY");
        assertThat(policy.evaluate(at("2026-09-07T08:59:00")).reason()).isEqualTo("OUTSIDE_SESSION");
        assertThat(policy.evaluate(at("2026-09-07T15:21:00")).reason()).isEqualTo("OUTSIDE_SESSION");
    }

    private MarketSessionPolicy policy(List<LocalDate> holidays) {
        return new MarketSessionPolicy(new MarketWideScheduleProperties(true, 120, 30, false,
                LocalTime.of(9, 0), LocalTime.of(15, 20), LocalTime.of(14, 0),
                Duration.ofMinutes(5), Duration.ofMinutes(2), holidays));
    }

    private Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SEOUL).toInstant();
    }
}
