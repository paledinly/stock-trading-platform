/*
 * RealtimeGapRepairService(끊긴 구간 5분봉 채우기) 테스트예요. 다시 구독된 칸이 끝나기 전에는 기다리고,
 * 끝난 뒤에는 비었거나 미확정인 칸만 KIS 분봉 보충에 넘기며, 다 채우면 기록을 남기고 끝내는지 확인해요.
 */
package com.sunmo.stockplatform.market.application;

import com.sunmo.stockplatform.candle.application.CandleBackfillService;
import com.sunmo.stockplatform.candle.domain.CandleSource;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RealtimeGapRepairServiceTest {
    static final Instant B1000 = Instant.parse("2026-10-16T01:00:00Z");
    static final Instant B1005 = Instant.parse("2026-10-16T01:05:00Z");

    @Test
    void fillsMissingAndPartialBarsAfterTheResumeBucketSettles() {
        var coverage = RealtimeCoverage.untracked();
        coverage.subscribed("A", Instant.parse("2026-10-15T23:59:00Z"));
        coverage.disconnected(Instant.parse("2026-10-16T01:02:10Z"));
        coverage.subscribed("A", Instant.parse("2026-10-16T01:07:30Z"));
        var backfill = mock(CandleBackfillService.class);
        var stocks = mock(StockRepository.class);
        var candles = mock(StockCandleRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(1L);
        when(stocks.findByStockCodeAndActiveTrue("A")).thenReturn(Optional.of(stock));
        StockCandle partial = new StockCandle(stock, B1000, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                1, BigDecimal.ONE, false, 0, CandleSource.REALTIME);
        StockCandle filled0 = new StockCandle(stock, B1000, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                9, BigDecimal.ONE, true, 0, CandleSource.BACKFILL);
        StockCandle filled5 = new StockCandle(stock, B1005, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                9, BigDecimal.ONE, true, 0, CandleSource.BACKFILL);
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("5M"), any(), any())).thenReturn(List.of(partial), List.of(filled0, filled5));
        var service = new RealtimeGapRepairService(coverage, backfill, stocks, candles, mock(ClosingTradingCalendar.class), jdbc);

        service.repairDue(Instant.parse("2026-10-16T01:10:00Z")); // 10:10: 10:05 칸 정착 전이라 기다려요
        verifyNoInteractions(backfill);

        service.repairDue(Instant.parse("2026-10-16T01:12:00Z"));
        verify(backfill).backfillToday(stock, List.of(B1000, B1005), Instant.parse("2026-10-16T01:12:00Z"));
        verify(jdbc, times(2)).update(anyString(), any(Object[].class));
        assertThat(coverage.repairable()).isEmpty();
    }
}
