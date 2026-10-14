/*
 * MarketSessionPolicy가 전시장 스캔을 돌릴 수 있는 시각인지와, 어떤 시간 구간(버킷)에 속하는지 판단하는지 확인해요.
 * 주말·휴장일·장외 시간에 스캔하거나 버킷을 잘못 나누면 수집 기록이 어긋나므로 중요해요.
 */
package com.sunmo.stockplatform.marketwide;

import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import com.sunmo.stockplatform.marketwide.application.MarketSessionPolicy;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** 전시장 스캔 세션 판정 규칙을 점검하는 테스트예요. */
class MarketSessionPolicyTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * 14:00 전에는 5분 구간, 이후에는 2분 구간으로 나누는지 확인해요.
     * 준비: 13:57:45와 14:07:45 시각을 평가해요.
     * 기대: 앞은 13:55 구간(실행 가능), 뒤는 14:06 구간이에요.
     */
    @Test
    void usesFiveMinuteBucketsBeforeLateSessionAndTwoMinutesAfterward() {
        MarketSessionPolicy policy = policy(List.of());
        var normal = policy.evaluate(at("2026-09-07T13:57:45"));
        var late = policy.evaluate(at("2026-09-07T14:07:45"));

        assertThat(normal.eligible()).isTrue();
        assertThat(normal.bucket()).isEqualTo(at("2026-09-07T13:55:00"));
        assertThat(late.bucket()).isEqualTo(at("2026-09-07T14:06:00"));
    }

    /**
     * 주말, 설정된 휴장일, 장 시간 밖은 거부하는지 확인해요.
     * 준비: 9월 8일을 휴장일로 설정하고 일요일, 휴장일, 08:59, 15:21을 평가해요.
     * 기대: 사유가 각각 WEEKEND, CONFIGURED_HOLIDAY, OUTSIDE_SESSION이에요.
     */
    @Test
    void rejectsWeekendConfiguredHolidayAndOutsideSession() {
        MarketSessionPolicy policy = policy(List.of(LocalDate.of(2026, 9, 8)));
        assertThat(policy.evaluate(at("2026-09-06T10:00:00")).reason()).isEqualTo("WEEKEND");
        assertThat(policy.evaluate(at("2026-09-08T10:00:00")).reason()).isEqualTo("CONFIGURED_HOLIDAY");
        assertThat(policy.evaluate(at("2026-09-07T08:59:00")).reason()).isEqualTo("OUTSIDE_SESSION");
        assertThat(policy.evaluate(at("2026-09-07T15:21:00")).reason()).isEqualTo("OUTSIDE_SESSION");
    }

    /** 09:00~15:20 장, 14:00 이후 2분 구간을 쓰는 테스트용 정책을 만드는 도우미예요. */
    private MarketSessionPolicy policy(List<LocalDate> holidays) {
        return new MarketSessionPolicy(new MarketWideScheduleProperties(true, 120, 30, false,
                LocalTime.of(9, 0), LocalTime.of(15, 20), LocalTime.of(14, 0),
                Duration.ofMinutes(5), Duration.ofMinutes(2), holidays));
    }

    /** 서울 시간 문자열을 Instant로 바꾸는 도우미예요. */
    private Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SEOUL).toInstant();
    }
}
