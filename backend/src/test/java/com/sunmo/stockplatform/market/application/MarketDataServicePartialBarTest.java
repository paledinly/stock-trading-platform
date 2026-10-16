/*
 * 재시작·재연결 빈 구간에 걸친 실시간 봉(일부 체결만 담은 봉)을 MarketDataService가 미확정으로 저장하고,
 * 이미 확정된 봉(보충 봉 등)은 덮지 않으며, 평소 봉은 지금처럼 확정으로 저장하는지 확인해요.
 */
package com.sunmo.stockplatform.market.application;

import com.sunmo.stockplatform.analytics.application.DetectionPerformanceTracker;
import com.sunmo.stockplatform.candle.domain.CandleSource;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.market.feature.application.MarketFeatureEngine;
import com.sunmo.stockplatform.scanner.application.ScannerEngine;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MarketDataServicePartialBarTest {
    static final Instant BUCKET = Instant.parse("2026-10-16T00:35:00Z"); // 09:35 KST
    final StockRepository stocks = mock(StockRepository.class);
    final StockCandleRepository candles = mock(StockCandleRepository.class);
    final RealtimeCoverage coverage = mock(RealtimeCoverage.class);
    final Stock stock = mock(Stock.class);

    MarketDataService service() {
        RealtimeMarketProperties properties = mock(RealtimeMarketProperties.class);
        when(properties.candleWatermark()).thenReturn(Duration.ofSeconds(2));
        when(stock.getId()).thenReturn(1L);
        when(stocks.findByStockCodeAndActiveTrue("A")).thenReturn(Optional.of(stock));
        return new MarketDataService(mock(QuoteStateStore.class), stocks, candles, mock(MarketEventGateway.class),
                properties, mock(ScannerEngine.class), mock(DetectionPerformanceTracker.class), new RealtimeDiagnostics(),
                mock(MarketFeatureEngine.class), mock(ApplicationEventPublisher.class), mock(PlatformTransactionManager.class),
                coverage);
    }

    /** 09:35:41 틱 하나로 09:35 봉을 열고 09:40:05에 확정시켜요. */
    void closeOneBar(MarketDataService service) {
        Instant tickAt = BUCKET.plusSeconds(41);
        service.onTick(new MarketTick("A", LocalDate.of(2026, 10, 16), tickAt, BigDecimal.TEN, 1, 1, BigDecimal.TEN, 1),
                tickAt, false);
        service.closeExpired(BUCKET.plusSeconds(305));
    }

    @Test
    void partialBarIsStoredAsNotFinal() {
        when(coverage.partial("A", BUCKET)).thenReturn(true);
        when(candles.findByStockIdAndTimeframeAndStartTime(1L, "5M", BUCKET)).thenReturn(Optional.empty());
        closeOneBar(service());

        ArgumentCaptor<StockCandle> saved = ArgumentCaptor.forClass(StockCandle.class);
        verify(candles).save(saved.capture());
        assertThat(saved.getValue().isFinalCandle()).isFalse();
        assertThat(saved.getValue().getSource()).isEqualTo(CandleSource.REALTIME);
    }

    @Test
    void partialBarDoesNotReplaceAFinalBar() {
        when(coverage.partial("A", BUCKET)).thenReturn(true);
        StockCandle filled = new StockCandle(stock, BUCKET, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                100, BigDecimal.TEN, true, 0, CandleSource.BACKFILL);
        when(candles.findByStockIdAndTimeframeAndStartTime(1L, "5M", BUCKET)).thenReturn(Optional.of(filled));
        closeOneBar(service());

        verify(candles, never()).save(any());
        assertThat(filled.getVolume()).isEqualTo(100);
    }

    @Test
    void normalBarIsStillStoredAsFinal() {
        when(candles.findByStockIdAndTimeframeAndStartTime(eq(1L), eq("5M"), any())).thenReturn(Optional.empty());
        closeOneBar(service());

        ArgumentCaptor<StockCandle> saved = ArgumentCaptor.forClass(StockCandle.class);
        verify(candles).save(saved.capture());
        assertThat(saved.getValue().isFinalCandle()).isTrue();
    }
}
