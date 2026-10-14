/*
 * ClosingTradingCalendar(마감 전략용 거래일 달력)가 다음 거래일을 올바르게 계산하는지 확인해요.
 * 다음 거래일이 틀리면 가상 진입 후 성과를 엉뚱한 날 기준으로 추적하게 되므로 중요해요.
 */
package com.sunmo.stockplatform.closing;

import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 달력 담당자처럼 휴장일을 건너뛰고 다음 거래일을 정확히 짚는지 확인하는 테스트예요. */
class ClosingTradingCalendarTest {
    /**
     * 무엇: 2026년 추석 연휴(검증된 휴장일)를 건너뛰는지 확인해요.
     * 입력: 추가 휴장일 설정이 없는 기본 달력, 기준일 2026-09-23.
     * 기대: 다음 거래일이 2026-09-28 이에요.
     */
    @Test
    void skipsVerifiedChuseokClosuresAfterSeptemberTwentyThird() {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 0, 0, false,
                null, null, null, null, null, List.of()));

        assertThat(calendar.nextTradingDay(LocalDate.of(2026, 9, 23)))
                .isEqualTo(LocalDate.of(2026, 9, 28));
    }
}
