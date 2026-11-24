package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.closing.application.MarketSessionHours;
import com.sunmo.stockplatform.kis.candle.KisMinuteCandleClient;
import com.sunmo.stockplatform.kis.candle.MinuteCandle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 하루 1분봉 흐름: 거꾸로 이어 받기, 시가 대비 등락, 상태 구분, 기억해 두기를 확인해요. */
class IntradayFlowServiceTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 25);

    private static MinuteCandle bar(LocalDate date, LocalTime time, int price) {
        Instant at = date.atTime(time).atZone(MarketSessionHours.ZONE).toInstant();
        BigDecimal p = BigDecimal.valueOf(price);
        return new MinuteCandle(at, p, p.add(BigDecimal.TEN), p.subtract(BigDecimal.TEN), p, 100, null);
    }

    /** through 시각을 포함해 그 전 count분의 봉(장 시작 전은 빼고)을 만들어요. */
    private static List<MinuteCandle> page(LocalDate date, LocalTime through, int count) {
        List<MinuteCandle> page = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LocalTime t = through.withSecond(0).minusMinutes(i);
            if (t.isBefore(LocalTime.of(9, 0))) break;
            page.add(bar(date, t, 1000 + t.toSecondOfDay() / 60 - 540));
        }
        return page;
    }

    private static ClosingTradingCalendar calendarAt(LocalDate date, LocalTime time) {
        ClosingTradingCalendar calendar = mock(ClosingTradingCalendar.class);
        when(calendar.now()).thenReturn(date.atTime(time).atZone(MarketSessionHours.ZONE).toInstant());
        when(calendar.isTradingDay(any())).thenReturn(true);
        return calendar;
    }

    @Test
    void stitchesPastDayFromCloseBackToOpen() {
        KisMinuteCandleClient kis = mock(KisMinuteCandleClient.class);
        LocalDate past = DAY.minusDays(1);
        when(kis.fetchHistory(eq("000010"), eq(past), any())).thenAnswer(call -> page(past, call.getArgument(2), 120));
        IntradayFlowService service = new IntradayFlowService(kis, calendarAt(DAY, LocalTime.of(10, 0)));

        IntradayFlowService.Flow flow = service.flow("000010", past);

        assertThat(flow.status()).isEqualTo("OK");
        assertThat(flow.live()).isFalse();
        assertThat(flow.bars()).hasSize(391);
        assertThat(flow.bars().get(0).time()).isEqualTo("09:00");
        assertThat(flow.bars().get(390).time()).isEqualTo("15:30");
        assertThat(flow.open()).isEqualByComparingTo("1000");
        assertThat(flow.last()).isEqualByComparingTo("1390");
        assertThat(flow.changeFromOpenPct()).isEqualTo(39.0);
        verify(kis, times(4)).fetchHistory(eq("000010"), eq(past), any());

        service.flow("000010", past);
        verify(kis, times(4)).fetchHistory(eq("000010"), eq(past), any());
    }

    @Test
    void todayUsesTheIntradayEndpointUpToNow() {
        KisMinuteCandleClient kis = mock(KisMinuteCandleClient.class);
        when(kis.fetch(eq("000010"), any())).thenAnswer(call -> page(DAY, call.getArgument(1), 30));
        IntradayFlowService service = new IntradayFlowService(kis, calendarAt(DAY, LocalTime.of(10, 5, 30)));

        IntradayFlowService.Flow flow = service.flow("000010", DAY);

        assertThat(flow.status()).isEqualTo("OK");
        assertThat(flow.live()).isTrue();
        assertThat(flow.bars()).hasSize(66);
        assertThat(flow.bars().get(65).time()).isEqualTo("10:05");
        verify(kis, times(3)).fetch(eq("000010"), any());
    }

    @Test
    void refetchesAfterTheCloseWhatWasSeenDuringTheSession() {
        KisMinuteCandleClient kis = mock(KisMinuteCandleClient.class);
        when(kis.fetch(eq("000010"), any())).thenAnswer(call -> page(DAY, call.getArgument(1), 30));
        ClosingTradingCalendar calendar = calendarAt(DAY, LocalTime.of(14, 0, 10));
        IntradayFlowService service = new IntradayFlowService(kis, calendar);

        assertThat(service.flow("000010", DAY).bars().get(300).time()).isEqualTo("14:00");

        when(calendar.now()).thenReturn(DAY.atTime(16, 50).atZone(MarketSessionHours.ZONE).toInstant());
        IntradayFlowService.Flow after = service.flow("000010", DAY);

        assertThat(after.live()).isFalse();
        assertThat(after.bars()).hasSize(391);
        assertThat(after.bars().get(390).time()).isEqualTo("15:30");

        int calls = mockingDetails(kis).getInvocations().size();
        service.flow("000010", DAY);
        assertThat(mockingDetails(kis).getInvocations()).hasSize(calls);
    }

    @Test
    void tellsWhyThereIsNothingToShow() {
        KisMinuteCandleClient kis = mock(KisMinuteCandleClient.class);
        IntradayFlowService service = new IntradayFlowService(kis, calendarAt(DAY, LocalTime.of(8, 30)));

        assertThat(service.flow("000010", DAY).status()).isEqualTo("NOT_STARTED");
        assertThat(service.flow("000010", DAY.plusDays(1)).status()).isEqualTo("FUTURE");
        when(kis.fetchHistory(any(), any(), any())).thenThrow(new IllegalStateException("down"));
        assertThat(service.flow("000010", DAY.minusDays(1)).status()).isEqualTo("UNAVAILABLE");
        verify(kis, never()).fetch(any(), any());
    }
}
