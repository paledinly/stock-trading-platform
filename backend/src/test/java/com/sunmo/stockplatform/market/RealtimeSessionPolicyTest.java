/*
 * RealtimeSessionPolicy가 실시간 연결을 열어도 되는 시간과, 받아들일 틱 시간 범위를 정하는지 확인해요.
 * 장 마감 동시호가 체결(15:30 무렵)은 받되 장외·전일 틱은 버려야 5분봉과 종가가 정확해요.
 */
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

/** 실시간 연결 시간대와 틱 수용 범위를 점검하는 테스트예요. */
class RealtimeSessionPolicyTest {
    /**
     * 지정한 서울 시각으로 고정한 시계(고정 시계 Clock)와 10월 9일 휴장 설정으로 정책을 만드는 도우미예요.
     * ClosingTradingCalendar(휴장일·다음 거래일 정책)를 그대로 써요.
     */
    private RealtimeSessionPolicy at(String time) {
        var schedule = new MarketWideScheduleProperties(false, 0, 0, false, null, null, null, null, null,
                List.of(LocalDate.of(2026, 10, 9)));
        var clock = Clock.fixed(LocalDateTime.parse(time).atZone(ClosingTradingCalendar.ZONE).toInstant(), ZoneOffset.UTC);
        return new RealtimeSessionPolicy(new ClosingTradingCalendar(schedule, clock),
                new RealtimeSessionProperties(Duration.ofMinutes(1), Duration.ofMinutes(1)));
    }
    /**
     * 거래일의 08:59~15:30:59 사이에만 연결을 허용하는지 확인해요(마감 동시호가와 수신 여유 포함).
     * 준비: 경계 시각들과 토요일(10/3), 설정 휴장일(10/9), 9/24를 평가해요.
     * 기대: 08:59:00~15:30:59만 true이고 나머지는 false예요.
     * 9/24는 휴장 설정에 없는데도 false여서, 캘린더에 내장된 추석 연휴 정보로 막히는 것으로 보여요.
     */
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
    /**
     * 무작위 마감 시각의 종가 체결은 받고, 장전·장후·전일 틱은 버리는지 확인해요.
     * 준비: 15:30:40 시점 정책으로 여러 시각의 틱을 평가해요.
     * 기대: 15:31:00까지는 수용(15:30:30 체결은 마감 동시호가 체결로 표시), 15:31:01 이후·08:59:59·전일 틱은 거부해요.
     */
    @Test void acceptsRandomEndClosingPrintButNotPremarketAfterHoursOrPreviousDay() {
        var policy = at("2026-10-01T15:30:40");
        assertThat(policy.accepts(tick("2026-10-01T15:19:59"))).isTrue();
        assertThat(policy.closingAuctionPrint(tick("2026-10-01T15:19:59"))).isFalse();
        assertThat(policy.accepts(tick("2026-10-01T15:30:00"))).isTrue();
        assertThat(policy.accepts(tick("2026-10-01T15:30:01"))).isTrue();
        assertThat(policy.accepts(tick("2026-10-01T15:30:30"))).isTrue();
        assertThat(policy.closingAuctionPrint(tick("2026-10-01T15:30:30"))).isTrue();
        assertThat(policy.accepts(tick("2026-10-01T15:31:00"))).isTrue();
        assertThat(policy.accepts(tick("2026-10-01T15:31:01"))).isFalse();
        assertThat(policy.accepts(tick("2026-10-01T15:40:00"))).isFalse();
        assertThat(policy.accepts(tick("2026-10-01T08:59:59"))).isFalse();
        assertThat(policy.accepts(tick("2026-09-30T15:30:00"))).isFalse();
    }
    /** 서울 시각 문자열로 테스트용 틱을 만드는 도우미예요. */
    private MarketTick tick(String local) {
        var time = LocalDateTime.parse(local);
        return new MarketTick("005930", time.toLocalDate(), time.atZone(ClosingTradingCalendar.ZONE).toInstant(),
                BigDecimal.TEN, 1, 1, BigDecimal.TEN, 1);
    }
}
