/*
 * CandleQueryService가 오늘 5분봉 조회 때 거래일에만 빈 칸 보충(KIS 호출)을 시도하는지 확인해요.
 * 주말·휴장일에는 봉이 없는 게 정상이라, 보충을 시도하면 쓸모없는 KIS 호출과 잘못된 빈 칸 진단만 생겨요.
 */
package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.stock.application.StockService;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.junit.jupiter.api.Test;

import java.time.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 봉 조회 시 보충 시도 조건을 점검하는 테스트예요. */
class CandleQueryServiceTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final StockService stocks = mock(StockService.class);
    private final StockCandleRepository candles = mock(StockCandleRepository.class);
    private final CandleBackfillService backfill = mock(CandleBackfillService.class);
    private final ClosingTradingCalendar calendar = mock(ClosingTradingCalendar.class);
    private final CandleQueryService service = new CandleQueryService(stocks, candles, backfill,
            new RealtimeDiagnostics(), calendar);

    /**
     * 준비: 2026-10-03(토) 10:00에 오늘 5분봉을 조회해요. 달력은 거래일이 아니라고 답해요.
     * 기대: 보충 서비스와 오늘 봉 조회 없이 요청 구간만 읽어요.
     */
    @Test
    void skipsBackfillOnNonTradingDay() {
        LocalDate date = LocalDate.of(2026, 10, 3);
        prepare(date, false);

        service.get("005930", "5M", date.atStartOfDay(SEOUL).toInstant(), date.atTime(16, 0).atZone(SEOUL).toInstant());

        verifyNoInteractions(backfill);
        verify(candles).findByStockIdAndTimeframeAndStartTimeBetweenOrderByStartTimeAsc(eq(1L), eq("5M"), any(), any());
        verify(candles, never()).findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                anyLong(), anyString(), any(), any());
    }

    /**
     * 준비: 2026-10-06(화, 거래일) 10:00에 오늘 5분봉을 조회해요(저장된 봉 없음).
     * 기대: 09:00~09:55 빈 칸 12개로 보충을 시도해요.
     */
    @Test
    void backfillsOnTradingDay() {
        LocalDate date = LocalDate.of(2026, 10, 6);
        Stock stock = prepare(date, true);

        service.get("005930", "5M", date.atStartOfDay(SEOUL).toInstant(), date.atTime(16, 0).atZone(SEOUL).toInstant());

        verify(backfill).backfillToday(eq(stock), argThat(gaps -> gaps.size() == 12), any());
    }

    /**
     * 보충 서비스가 KIS 분봉 호출을 트랜잭션 밖에서 하고, 저장할 때만 트랜잭션을 여는지 확인해요.
     * 준비: 09:00 빈 칸 하나, KIS는 09:00~09:04 분봉 5개를 돌려줘요.
     * 기대: KIS 호출이 트랜잭션 시작보다 먼저이고, 봉 1개를 저장해요.
     */
    @Test
    void backfillFetchesFromKisBeforeOpeningTheTransaction() {
        var client = mock(com.sunmo.stockplatform.kis.candle.KisMinuteCandleClient.class);
        var transactions = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var service = new CandleBackfillService(client, candles,
                new com.sunmo.stockplatform.candle.config.CandleBackfillProperties(true, Duration.ofMinutes(1), 1),
                new RealtimeDiagnostics(), transactions);
        Instant nine = LocalDate.of(2026, 10, 6).atTime(9, 0).atZone(SEOUL).toInstant();
        java.util.List<com.sunmo.stockplatform.kis.candle.MinuteCandle> page = new java.util.ArrayList<>();
        for (int minute = 0; minute < 5; minute++)
            page.add(new com.sunmo.stockplatform.kis.candle.MinuteCandle(nine.plusSeconds(60L * minute),
                    java.math.BigDecimal.TEN, java.math.BigDecimal.TEN, java.math.BigDecimal.TEN, java.math.BigDecimal.TEN,
                    1, java.math.BigDecimal.valueOf(10L * (minute + 1))));
        when(client.fetch(eq("005930"), any())).thenReturn(page);
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(1L);
        when(stock.getStockCode()).thenReturn("005930");

        int saved = service.backfillToday(stock, java.util.List.of(nine), nine.plusSeconds(600));

        org.assertj.core.api.Assertions.assertThat(saved).isEqualTo(1);
        var order = inOrder(client, transactions, candles);
        order.verify(client).fetch(eq("005930"), any());
        order.verify(transactions).getTransaction(any());
        order.verify(candles).save(any());
    }

    /** 10:00 KST를 현재 시각으로, 거래일 여부를 정해 둔 목(mock) 상태를 만드는 도우미예요. */
    private Stock prepare(LocalDate date, boolean tradingDay) {
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(1L);
        when(stocks.getByCode("005930")).thenReturn(stock);
        when(calendar.now()).thenReturn(date.atTime(10, 0).atZone(SEOUL).toInstant());
        when(calendar.isTradingDay(date)).thenReturn(tradingDay);
        return stock;
    }
}
