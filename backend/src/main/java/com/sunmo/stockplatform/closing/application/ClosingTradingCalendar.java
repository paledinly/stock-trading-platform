/*
 * 마감 추천 전략이 쓰는 거래일 달력이에요. 거래일 여부, 다음·이전 거래일, 정규장 시작·종료 시각을 알려 줘요.
 * 공식 개장 정보(OfficialTradingDays)를 받아 둔 날은 그 값을 우선하고, 없으면 주말·확인된 임시 휴장일·설정 휴장일로 판단해요.
 * 추천 생성, 가상 진입, 다음 거래일 성과 측정 등 closing 패키지 대부분이 이 달력을 써요.
 */
package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.Optional;
import java.util.Set;

/**
 * 마감 전략의 휴장일·다음 거래일 정책을 한곳에서 정하는 달력이에요.
 * 거래소에서 받아 둔 공식 일정이 있으면 그걸 보고, 없으면 벽에 붙은 종이 달력(주말·적어 둔 휴장일)을 보는
 * 일정 담당자라고 생각하면 돼요. 정적 휴장일 목록은 자동으로 갱신되는 공식 달력이 아니에요.
 */
@Component
public class ClosingTradingCalendar {
    // 모든 날짜·시각 판단은 한국 시간(Asia/Seoul) 기준이에요.
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    // KRX closures confirmed for the audited period. Additional closures remain configurable.
    private static final Set<LocalDate> VERIFIED_SPECIAL_CLOSURES = Set.of(
            LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25));
    private final MarketWideScheduleProperties schedule;
    private final Clock clock;
    private final OfficialTradingDays official;

    /**
     * 스프링이 쓰는 생성자예요. 실제 시스템 시계(UTC)와 공식 거래일 정보를 연결해요.
     * 받는 것: 휴장일 설정, 공식 거래일 조회기.
     */
    @Autowired
    public ClosingTradingCalendar(MarketWideScheduleProperties schedule, OfficialTradingDays official) {
        this(schedule, Clock.systemUTC(), official);
    }

    /**
     * 공식 거래일 정보 없이 설정 휴장일만으로 판단하는 생성자예요(주로 테스트용으로 보여요).
     * 받는 것: 휴장일 설정.
     */
    public ClosingTradingCalendar(MarketWideScheduleProperties schedule) {
        this(schedule, Clock.systemUTC());
    }

    /**
     * 시계를 바꿔 끼울 수 있는 생성자예요. 테스트에서 고정 시각을 넣을 때 써요.
     * 받는 것: 휴장일 설정, 시계.
     */
    public ClosingTradingCalendar(MarketWideScheduleProperties schedule, Clock clock) {
        this(schedule, clock, null);
    }

    /**
     * 모든 생성자가 최종적으로 부르는 생성자예요.
     * 받는 것: 휴장일 설정, 시계, 공식 거래일 조회기(없으면 null).
     */
    public ClosingTradingCalendar(MarketWideScheduleProperties schedule, Clock clock, OfficialTradingDays official) {
        this.schedule = schedule;
        this.clock = clock;
        this.official = official;
    }

    // now(): 현재 시각, today(): 한국 시간 기준 오늘 날짜를 돌려줘요.
    public Instant now() { return clock.instant(); }
    public LocalDate today() { return now().atZone(ZONE).toLocalDate(); }

    /**
     * Provider open flags win where fetched; otherwise weekends, verified closures and configured holidays.
     * 하는 일: 그 날짜가 거래일인지 판단해요. 공식 개장 여부를 받아 둔 날이면 그 값을, 아니면 정적 규칙을 써요.
     * 받는 것: 날짜. 돌려주는 것: 거래일이면 true.
     */
    public boolean isTradingDay(LocalDate date) {
        if (official != null) {
            Optional<Boolean> open = official.marketOpen(date);
            if (open.isPresent()) return open.get();
        }
        return isStaticTradingDay(date);
    }

    /**
     * 공식 정보 없이 정적 규칙만으로 거래일인지 판단해요(주말, 확인된 임시 휴장일, 설정 휴장일 제외).
     * 받는 것: 날짜. 돌려주는 것: 거래일이면 true.
     */
    public boolean isStaticTradingDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY
                && !VERIFIED_SPECIAL_CLOSURES.contains(date) && !schedule.holidays().contains(date);
    }

    /**
     * 주어진 날짜 다음의 첫 거래일을 찾아요. 마감 추천의 성과 측정일을 정할 때 써요.
     * 받는 것: 기준 날짜. 돌려주는 것: 기준 날짜보다 뒤인 가장 가까운 거래일.
     */
    public LocalDate nextTradingDay(LocalDate date) {
        LocalDate next = date.plusDays(1);
        while (!isTradingDay(next)) next = next.plusDays(1);
        return next;
    }

    /**
     * 주어진 날짜 이전의 가장 가까운 거래일을 찾아요.
     * 받는 것: 기준 날짜. 돌려주는 것: 기준 날짜보다 앞인 가장 가까운 거래일.
     */
    public LocalDate previousTradingDay(LocalDate date) {
        LocalDate previous = date.minusDays(1);
        while (!isTradingDay(previous)) previous = previous.minusDays(1);
        return previous;
    }

    // Regular-session hours are independent of the market-wide scanner's 15:20 cutoff.
    // open(): 그 날짜 개장(평소 09:00), close(): 정규장 종료(평소 15:30) 시각을 Instant로 돌려줘요.
    // 수능일처럼 시간이 바뀐 날은 MarketSessionHours 설정을 따라요.
    public Instant open(LocalDate date) { return date.atTime(MarketSessionHours.open(date)).atZone(ZONE).toInstant(); }
    public Instant close(LocalDate date) { return date.atTime(MarketSessionHours.close(date)).atZone(ZONE).toInstant(); }
}
