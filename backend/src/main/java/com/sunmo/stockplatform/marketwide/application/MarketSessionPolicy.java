/*
 * 지금 시각이 시장 전체 스캔을 돌려도 되는 시간인지 판단하는 파일이에요.
 * 주말, 설정된 휴장일, 장 시간 밖이면 거절하고, 가능하면 "몇 시 몇 분 회차"인지(버킷)를 계산해 줘요.
 * MarketWideScanScheduler가 주기적으로 이 판단을 물어봐요.
 */
package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.springframework.stereotype.Component;
import java.time.*;

/**
 * 시장 전체 스캔을 지금 돌려도 되는지, 돌린다면 어느 회차인지 정해 주는 클래스예요.
 * 가게 문 앞의 "영업 시간 안내판"을 보고 지금 들어가도 되는지, 몇 시 타임 손님인지 알려 주는 안내원과 같아요.
 */
@Component
public class MarketSessionPolicy {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private final MarketWideScheduleProperties properties;

    /**
     * 하는 일: 스캔 일정 설정을 받아 정책 객체를 만들어요.
     * 받는 것: 장 시작/마감 시각, 간격, 휴장일 목록이 담긴 설정값.
     */
    public MarketSessionPolicy(MarketWideScheduleProperties properties) {
        this.properties = properties;
    }

    /**
     * 하는 일: 주어진 시각에 스캔을 돌려도 되는지 판단하고, 된다면 회차 시각(버킷)을 계산해요.
     * 받는 것: 판단할 시각(now).
     * 돌려주는 것: 가능 여부, 회차 시각(불가면 null), 사유 문자열을 담은 Decision.
     */
    public Decision evaluate(Instant now) {
        // 준비: 한국 시간 기준으로 날짜와 요일을 구해요.
        ZonedDateTime marketNow = now.atZone(MARKET_ZONE);
        LocalDate date = marketNow.toLocalDate();
        DayOfWeek day = marketNow.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY)
            return new Decision(false, null, "WEEKEND");
        // ⚠️ 확인 필요: 설정에 적힌 정적 휴장일 목록만 보고 있어요.
        // 프로젝트 규칙상 휴장일은 ClosingTradingCalendar 정책을 따라야 하므로, 목록에 없는 임시 휴장일엔 스캔이 돌 수 있어요.
        if (properties.holidays().contains(date))
            return new Decision(false, null, "CONFIGURED_HOLIDAY");
        LocalTime time = marketNow.toLocalTime();
        if (time.isBefore(properties.open()) || time.isAfter(properties.close()))
            return new Decision(false, null, "OUTSIDE_SESSION");
        // 처리: 장 후반(lateStart 이후)에는 다른 간격을 쓰고, 간격은 최소 60초로 막아요.
        Duration interval = time.isBefore(properties.lateStart())
                ? properties.normalInterval() : properties.lateInterval();
        long intervalSeconds = Math.max(60, interval.toSeconds());
        // 결과: 자정부터 흐른 시간을 간격 단위로 내림해서 같은 회차면 같은 버킷 시각이 나오게 해요.
        long elapsed = Duration.between(date.atStartOfDay(), marketNow.toLocalDateTime()).toSeconds();
        long bucketElapsed = Math.floorDiv(elapsed, intervalSeconds) * intervalSeconds;
        Instant bucket = date.atStartOfDay(MARKET_ZONE).plusSeconds(bucketElapsed).toInstant();
        return new Decision(true, bucket, "ELIGIBLE");
    }

    /**
     * 스캔 가능 여부 판단 결과를 담는 기록이에요.
     * eligible은 가능 여부, bucket은 회차 시각(불가면 null), reason은 WEEKEND 같은 사유예요.
     */
    public record Decision(boolean eligible, Instant bucket, String reason) {
    }
}
