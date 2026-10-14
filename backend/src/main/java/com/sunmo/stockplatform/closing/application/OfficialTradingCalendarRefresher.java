/*
 * KIS(한국투자증권 API)의 국내 휴장일(시장이 쉬는 날) 조회 결과를 받아 OfficialTradingDays에 저장하는 갱신기예요.
 * 앱 시작 때와 매일 아침 07:40(기본값)에 시도하지만, 같은 날짜에는 한 번만 실제로 받아와요.
 * closing.calendar.kis-refresh-enabled=true일 때만 만들어지고, 받은 값은 ClosingTradingCalendar가 거래일 판정에 써요.
 */
package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.kis.calendar.KisHolidayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * 공식 개장 여부를 하루 한 번 받아 오는 클래스예요.
 * 매일 아침 학교 홈페이지에서 "오늘 휴교인지"를 한 번 확인해 달력에 적어 두는 당번이라고 생각하면 돼요. 확인에 실패하면 원래 달력(정적 정책)을 그대로 써요.
 * Fetches KIS market-open flags at most once per KST date (startup or morning cron).
 */
@Component
@ConditionalOnProperty(prefix = "closing.calendar", name = "kis-refresh-enabled", havingValue = "true")
public class OfficialTradingCalendarRefresher {
    private static final Logger log = LoggerFactory.getLogger(OfficialTradingCalendarRefresher.class);
    private final KisHolidayClient client;
    private final OfficialTradingDays days;
    private final ClosingTradingCalendar calendar;

    /**
     * 하는 일: 의존 객체를 받아 갱신기를 만들어요.
     * 받는 것: KIS 휴장일 조회 클라이언트, 공식 거래일 저장소, 거래일 달력.
     * 돌려주는 것: 없음(생성자).
     */
    public OfficialTradingCalendarRefresher(KisHolidayClient client, OfficialTradingDays days, ClosingTradingCalendar calendar) {
        this.client = client;
        this.days = days;
        this.calendar = calendar;
    }

    /**
     * 하는 일: 앱이 준비되면 바로 한 번 갱신을 시도해요.
     * 받는 것: 없음. 돌려주는 것: 없음.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() { refresh(); }

    /**
     * 하는 일: 스케줄러(정해진 시각에 자동 실행하는 장치)가 매일 07:40(설정으로 변경 가능)에 갱신을 시도해요.
     * 받는 것: 없음. 돌려주는 것: 없음.
     */
    @Scheduled(cron = "${closing.calendar.kis-refresh-cron:0 40 7 * * *}", zone = "Asia/Seoul")
    public void scheduled() { refresh(); }

    /**
     * 하는 일: 오늘(한국시간) 아직 받은 적이 없으면 KIS에서 날짜별 개장 여부를 받아 저장해요. 동시에 두 번 돌지 않게 synchronized예요.
     * 받는 것: 없음.
     * 돌려주는 것: 실제로 새로 저장했으면 true, 이미 받았거나 응답이 비었거나 실패하면 false.
     */
    public synchronized boolean refresh() {
        // 준비: 마지막으로 받은 날짜가 오늘 이후면(이미 오늘 받음) 다시 부르지 않아요.
        LocalDate today = calendar.today();
        Instant last = days.status().lastFetchedAt();
        if (last != null && !last.atZone(ClosingTradingCalendar.ZONE).toLocalDate().isBefore(today))
            return false;
        try {
            // 처리: 오늘 기준으로 KIS 개장 여부를 받아요. 빈 응답이면 아무것도 바꾸지 않고 정적 달력 정책을 유지해요.
            Map<LocalDate, Boolean> fetched = client.fetch(today);
            if (fetched.isEmpty()) {
                log.warn("KIS holiday response had no usable dates for {}; static calendar policy remains", today);
                return false;
            }
            // 정적 달력과 다른 날짜는 로그로 알리고, 공급자(KIS) 값을 우선 적용해요.
            fetched.forEach((date, open) -> {
                if (open != calendar.isStaticTradingDay(date))
                    log.info("KIS market-open flag {} for {} differs from static calendar; provider value applies", open, date);
            });
            // 결과: 받은 값을 DB와 메모리에 저장해요. 실패하면 경고만 남기고 정적 달력 정책을 유지해요.
            days.record(fetched, "KIS_CHK_HOLIDAY", calendar.now());
            return true;
        } catch (RuntimeException error) {
            log.warn("KIS holiday refresh failed; static calendar policy remains: {}", error.getMessage());
            return false;
        }
    }
}
