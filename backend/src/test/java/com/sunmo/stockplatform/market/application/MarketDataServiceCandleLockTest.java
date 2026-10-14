/*
 * MarketDataService의 봉 확정 스케줄러(closeExpired)와 틱 처리(onTick)가 같은 잠금으로 한 줄로 서는지 확인해요.
 * 두 스레드가 동시에 Scanner를 부르면 Scanner 내부 상태가 꼬일 수 있어 중요해요.
 */
package com.sunmo.stockplatform.market.application;

import com.sunmo.stockplatform.analytics.application.DetectionPerformanceTracker;
import com.sunmo.stockplatform.candle.application.CandleSnapshot;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.market.feature.application.MarketFeatureEngine;
import com.sunmo.stockplatform.scanner.application.ScannerEngine;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 봉 처리 잠금을 점검하는 테스트예요. */
class MarketDataServiceCandleLockTest {
    private final ScannerEngine scanner = mock(ScannerEngine.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final MarketDataService service = service();

    /**
     * 틱 처리 스레드가 Scanner 안에 있는 동안 closeExpired가 Scanner를 부르지 않고 기다리는지 확인해요.
     * 준비: 000660 틱으로 09:00 봉을 열고, 005930 틱의 미확정 관찰(observeIncomplete)이 래치(latch)에서 멈추게 해요.
     * 기대: 멈춘 동안 다른 스레드의 closeExpired는 evaluate를 부르지 못하고, 풀어 준 뒤에야 두 봉을 확정 평가해요.
     */
    @Test
    void closeExpiredWaitsWhileTheTickPathIsHandlingCandles() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            CandleSnapshot snapshot = invocation.getArgument(0);
            if (snapshot.stockCode().equals("005930")) {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            }
            return null;
        }).when(scanner).observeIncomplete(any());
        Instant open = Instant.parse("2026-10-06T00:00:01Z");
        service.onTick(tick("000660", open), open, false);

        Thread tickThread = new Thread(() -> service.onTick(tick("005930", open.plusSeconds(1)), open.plusSeconds(1), false));
        tickThread.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        Thread flushThread = new Thread(() -> service.closeExpired(Instant.parse("2026-10-06T00:10:00Z")));
        flushThread.start();
        flushThread.join(300);
        assertThat(flushThread.isAlive()).as("closeExpired waits for the tick path").isTrue();
        verify(scanner, never()).evaluate(any());

        release.countDown();
        tickThread.join(5000);
        flushThread.join(5000);
        assertThat(flushThread.isAlive()).isFalse();
        verify(scanner, times(2)).evaluate(any());
        verify(transactions, times(1)).getTransaction(any());
    }

    /**
     * 확정할 봉이 없으면 트랜잭션을 열지 않는지 확인해요.
     */
    @Test
    void closeExpiredOpensNoTransactionWhenNothingExpired() {
        service.closeExpired(Instant.parse("2026-10-06T00:10:00Z"));

        verifyNoInteractions(transactions);
        verifyNoInteractions(scanner);
    }

    /** 목(mock) 의존성으로 서비스를 만드는 도우미예요(봉 확정 여유 2초). */
    private MarketDataService service() {
        RealtimeMarketProperties properties = mock(RealtimeMarketProperties.class);
        when(properties.candleWatermark()).thenReturn(Duration.ofSeconds(2));
        return new MarketDataService(mock(QuoteStateStore.class), mock(StockRepository.class),
                mock(StockCandleRepository.class), mock(MarketEventGateway.class), properties, scanner,
                mock(DetectionPerformanceTracker.class), new RealtimeDiagnostics(), mock(MarketFeatureEngine.class),
                mock(ApplicationEventPublisher.class), transactions);
    }

    /** 가격 10원짜리 단순 틱을 만드는 도우미예요. */
    private MarketTick tick(String code, Instant at) {
        return new MarketTick(code, LocalDate.of(2026, 10, 6), at, BigDecimal.TEN, 1, 1, BigDecimal.TEN, 1);
    }
}
