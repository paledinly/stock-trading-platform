/*
 * DetectionPerformanceTracker(검출 성과 추적기)가 장 마감 확정을 ClosingTradingCalendar 정책에 맞게 하는지 확인해요.
 * 휴장일에는 확정하지 않고, 마감 가격에는 그 세션의 체결만 쓰며, 저장된 봉은 확정봉만 쓰고, 정규장 시각은 달력에서 받아요.
 * 오래된 가격이 마감 가격으로 들어가면 성과 통계가 조용히 왜곡되므로 중요해요.
 */
package com.sunmo.stockplatform.analytics;

import com.sunmo.stockplatform.analytics.application.DetectionPerformanceTracker;
import com.sunmo.stockplatform.analytics.domain.DetectionPerformance;
import com.sunmo.stockplatform.analytics.domain.PerformanceStatus;
import com.sunmo.stockplatform.analytics.infrastructure.DetectionPerformanceRepository;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.market.application.MarketEventGateway;
import com.sunmo.stockplatform.market.application.QuoteStateStore;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 성과 추적기의 장 마감 확정 규칙을 점검하는 테스트예요. */
class DetectionPerformanceTrackerCloseTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 평일 거래일(목요일)이에요. */
    private static final LocalDate SESSION = LocalDate.of(2026, 10, 8);
    /** 설정 휴장일로 넣는 평일(금요일, 한글날)이에요. */
    private static final LocalDate HOLIDAY = LocalDate.of(2026, 10, 9);
    private static final String CODE = "005930";

    private final DetectionPerformanceRepository repository = mock(DetectionPerformanceRepository.class);
    private final StockCandleRepository candles = mock(StockCandleRepository.class);
    private final QuoteStateStore quotes = mock(QuoteStateStore.class);
    private final MutableClock clock = new MutableClock();

    /**
     * 평일 휴장일에는 15:31 확정 작업이 아무것도 확정하지 않는지 확인해요.
     * 준비: 휴장일로 설정한 10/09 10:00에 추적 중인 성과를 올리고 15:31로 시계를 옮겨요.
     * 기대: finalizeClose와 저장이 한 번도 불리지 않아요.
     */
    @Test
    void skipsFinalizationOnWeekdayHoliday() {
        DetectionPerformance performance = performance(HOLIDAY);
        DetectionPerformanceTracker tracker = trackerStartedAt(HOLIDAY, performance, calendar(HOLIDAY));

        clock.set(at(HOLIDAY, 15, 31));
        tracker.finalizeMarketClose();

        verify(performance, never()).finalizeClose(any(), any());
        verify(repository, never()).save(any());
    }

    /**
     * 메모리 현재가가 지난 날 체결이면 쓰지 않고 저장된 확정봉 종가로 확정하는지 확인해요.
     * 준비: 현재가는 전날(10/07) 15:20 체결 9000원, 저장된 확정봉 종가는 10100원이에요.
     * 기대: 마감 가격으로 10100원이 들어가요.
     */
    @Test
    void ignoresRealtimeQuoteFromAnotherDay() {
        DetectionPerformance performance = performance(SESSION);
        when(quotes.get(CODE)).thenReturn(Optional.of(tick(at(SESSION.minusDays(1), 15, 20), "9000")));
        List<StockCandle> stored = List.of(candle(15, 20, "10100", true));
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                anyLong(), eq("5M"), any(), any())).thenReturn(stored);
        DetectionPerformanceTracker tracker = trackerStartedAt(SESSION, performance, calendar());

        clock.set(at(SESSION, 15, 31));
        tracker.finalizeMarketClose();

        verify(performance).finalizeClose(new BigDecimal("10100"), at(SESSION, 15, 31));
    }

    /**
     * 메모리 현재가가 그 세션 체결이면 그 가격으로 확정하는지 확인해요.
     * 준비: 현재가는 세션 당일 15:30 체결 10200원이에요.
     * 기대: 마감 가격으로 10200원이 들어가요.
     */
    @Test
    void usesRealtimeQuoteFromTheSession() {
        DetectionPerformance performance = performance(SESSION);
        when(quotes.get(CODE)).thenReturn(Optional.of(tick(at(SESSION, 15, 30), "10200")));
        DetectionPerformanceTracker tracker = trackerStartedAt(SESSION, performance, calendar());

        clock.set(at(SESSION, 15, 31));
        tracker.finalizeMarketClose();

        verify(performance).finalizeClose(new BigDecimal("10200"), at(SESSION, 15, 31));
    }

    /**
     * 저장된 봉으로 마감 가격을 고를 때 마지막 미확정봉은 건너뛰고 마지막 확정봉 종가를 쓰는지 확인해요.
     * 준비: 현재가 없음, 15:20 확정봉 종가 10100원, 15:25 미확정봉 종가 9900원이에요.
     * 기대: 마감 가격으로 10100원이 들어가요.
     */
    @Test
    void storedCloseUsesOnlyFinalCandles() {
        DetectionPerformance performance = performance(SESSION);
        when(quotes.get(CODE)).thenReturn(Optional.empty());
        List<StockCandle> stored = List.of(candle(15, 20, "10100", true), candle(15, 25, "9900", false));
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                anyLong(), eq("5M"), any(), any())).thenReturn(stored);
        DetectionPerformanceTracker tracker = trackerStartedAt(SESSION, performance, calendar());

        clock.set(at(SESSION, 15, 31));
        tracker.finalizeMarketClose();

        verify(performance).finalizeClose(new BigDecimal("10100"), at(SESSION, 15, 31));
    }

    /**
     * 정규장 시작·종료 시각을 고정값이 아니라 ClosingTradingCalendar에서 받는지 확인해요.
     * 준비: 달력이 이 날 마감을 14:30으로 알려 주게 하고, 현재가는 14:40 체결이에요.
     * 기대: 봉 조회 범위가 달력의 open~close이고, 마감 1분 뒤를 넘은 14:40 체결은 쓰지 않아요(봉 없음 → null).
     */
    @Test
    void sessionHoursComeFromTheCalendar() {
        DetectionPerformance performance = performance(SESSION);
        ClosingTradingCalendar calendar = spy(calendar());
        Instant earlyClose = at(SESSION, 14, 30);
        doReturn(earlyClose).when(calendar).close(SESSION);
        when(quotes.get(CODE)).thenReturn(Optional.of(tick(at(SESSION, 14, 40), "10300")));
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                anyLong(), eq("5M"), any(), any())).thenReturn(List.of());
        DetectionPerformanceTracker tracker = trackerStartedAt(SESSION, performance, calendar);

        clock.set(at(SESSION, 15, 31));
        tracker.finalizeMarketClose();

        verify(candles, atLeastOnce())
                .findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        10L, "5M", at(SESSION, 9, 0), earlyClose);
        verify(performance).finalizeClose(null, at(SESSION, 15, 31));
    }

    /** 세션 10:00에 서버가 켜져 PENDING 성과 하나를 추적 목록에 올린 추적기를 만들어요. */
    private DetectionPerformanceTracker trackerStartedAt(LocalDate date, DetectionPerformance performance,
            ClosingTradingCalendar calendar) {
        clock.set(at(date, 10, 0));
        when(repository.findWithDetectionByStatus(PerformanceStatus.PENDING)).thenReturn(List.of(performance));
        when(repository.save(performance)).thenReturn(performance);
        DetectionPerformanceTracker tracker = new DetectionPerformanceTracker(repository, candles, quotes,
                mock(MarketEventGateway.class), new RealtimeDiagnostics(), mock(PlatformTransactionManager.class),
                calendar);
        tracker.run(null);
        return tracker;
    }

    /** 움직이는 시계와 추가 정적 휴장일을 쓰는 거래일 달력을 만들어요. */
    private ClosingTradingCalendar calendar(LocalDate... holidays) {
        return new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null, null,
                null, null, List.of(holidays)), clock);
    }

    /** 그 세션 09:30에 검출된, 대기 상태 성과 목(mock, 가짜 객체)을 만들어요. */
    private DetectionPerformance performance(LocalDate session) {
        Stock stock = mock(Stock.class);
        when(stock.getStockCode()).thenReturn(CODE);
        when(stock.getId()).thenReturn(10L);
        ScannerDetection detection = mock(ScannerDetection.class);
        when(detection.getStock()).thenReturn(stock);
        when(detection.getSessionDate()).thenReturn(session);
        when(detection.getDetectedAt()).thenReturn(at(session, 9, 30));
        DetectionPerformance performance = mock(DetectionPerformance.class);
        when(performance.getDetectionId()).thenReturn(1L);
        when(performance.getDetection()).thenReturn(detection);
        when(performance.getStatus()).thenReturn(PerformanceStatus.PENDING);
        return performance;
    }

    /** 주어진 시각·가격의 체결 틱을 만들어요. */
    private MarketTick tick(Instant occurredAt, String price) {
        return new MarketTick(CODE, occurredAt.atZone(SEOUL).toLocalDate(), occurredAt, new BigDecimal(price), 1, 1,
                new BigDecimal(price), 1);
    }

    /** 세션 날짜의 주어진 시각에 시작하는 5분봉 목을 만들어요. */
    private StockCandle candle(int hour, int minute, String close, boolean finalCandle) {
        StockCandle candle = mock(StockCandle.class);
        when(candle.getStartTime()).thenReturn(at(SESSION, hour, minute));
        when(candle.getClose()).thenReturn(new BigDecimal(close));
        when(candle.getHigh()).thenReturn(new BigDecimal(close));
        when(candle.getLow()).thenReturn(new BigDecimal(close));
        when(candle.isFinalCandle()).thenReturn(finalCandle);
        return candle;
    }

    /** 서울 시간 날짜·시·분을 Instant로 바꿔요. */
    private static Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(SEOUL).toInstant();
    }

    /** 테스트 안에서 시각을 옮길 수 있는 시계예요. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.EPOCH;

        void set(Instant value) { now = value; }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }
    }
}
