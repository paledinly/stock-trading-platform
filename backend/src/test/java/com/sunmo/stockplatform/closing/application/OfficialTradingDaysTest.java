/*
 * OfficialTradingDays(KIS 휴장일 조회 결과 저장소)와 OfficialTradingCalendarRefresher, 그리고 이를 쓰는 ClosingTradingCalendar 를 함께 확인해요.
 * 공식 출처가 알려 준 날짜는 그 값을 따르고, 모르는 날짜나 조회 실패 시에는 정적 휴장일 정책으로 돌아가는지가 다음 거래일 계산의 핵심이에요.
 */
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

/** 공식 휴장일 정보와 정적 정책이 정해진 우선순위로 합쳐지는지 확인하는 테스트예요. */
class OfficialTradingDaysTest {
    static final LocalDate FRIDAY = LocalDate.of(2026, 10, 2);
    /** 정적 목록에는 없지만 공식 출처가 휴장으로 알려 주는 대체공휴일이에요. */
    static final LocalDate SUBSTITUTE_HOLIDAY = LocalDate.of(2026, 10, 5);
    /** 조회 시각이에요(한국시간 2026-10-02 07:40). */
    static final Instant MORNING = Instant.parse("2026-10-01T22:40:00Z");
    EmbeddedDatabase db;
    JdbcTemplate jdbc;

    /** H2(테스트용 메모리 DB)에 V31 거래일 테이블을 만들어요. */
    @BeforeEach void setup() {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(db);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V31__create_trading_calendar_day.sql")).execute(db);
    }
    /** 테스트마다 메모리 DB 를 닫아요. */
    @AfterEach void close() { db.shutdown(); }

    /** 고정 시계 Clock 과 추가 정적 휴장일(holidays)을 받아 공식 거래일 정보를 쓰는 달력을 만들어요. */
    ClosingTradingCalendar calendar(OfficialTradingDays days, Instant now, LocalDate... holidays) {
        return new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null, null,
                null, null, List.of(holidays)), Clock.fixed(now, ZoneOffset.UTC), days);
    }

    /**
     * 무엇: 공식 출처가 아는 날짜는 그 값이 이기고, 모르는 날짜는 정적 정책이 적용되는지 확인해요.
     * 입력: KIS 기록(10/05 휴장, 10/02 개장), 기록 없는 10/07·10/09, 그리고 10/09 를 정적 휴장일로 넣은 달력.
     * 기대: 10/05 는 정적으로는 거래일이지만 실제 판정은 휴장, 10/02 다음 거래일은 10/06, 10/09 는 설정했을 때만 휴장이에요.
     */
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

    /**
     * 무엇: 기록한 거래일 정보가 DB 에 남아 재시작 후에도 읽히고, 같은 날짜의 새 조회 결과가 이전 값을 덮어쓰는지 확인해요.
     * 입력: 10/05 를 개장으로 기록한 뒤 1분 뒤 휴장으로 다시 기록하고, 새 인스턴스로 읽어요.
     * 기대: 10/05 는 휴장(false), 10/02 는 모름(empty), 아는 날짜 1개, 마지막 조회 시각은 1분 뒤, 테이블 행 1개.
     */
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

    /**
     * 무엇: 갱신기가 KIS 조회 실패 시 정적 정책을 유지하고, 성공 뒤에는 같은 날 다시 호출하지 않는지 확인해요.
     * 입력: 첫 호출은 예외, 두 번째 호출은 10/05 휴장 정보를 돌려주는 KIS 클라이언트 목(mock, 가짜 객체).
     * 기대: refresh 결과 false → true → false, 10/05 판정은 거래일 → 휴장으로 바뀌고, KIS 호출은 총 2회예요.
     */
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
