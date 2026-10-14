/*
 * TrajectoryService.flush에서 한 종목의 스냅샷 계산이 실패해도 다른 종목과 1분 집계 저장이 계속되는지 확인해요.
 * 한 종목 때문에 1초 묶음 전체가 롤백되면 같은 자료로 매번 실패해 1분 집계가 계속 쌓이다 버려질 수 있어요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 스냅샷 실패 격리를 점검하는 테스트예요. */
class TrajectorySnapshotIsolationTest {
    /**
     * 준비: 14:29(UTC 05:29) 분에 BAD, 005930 두 종목 틱을 넣고, BAD의 종목 조회만 예외를 던지게 해요.
     *       현재 시각은 14:30:03이라 두 종목의 14:29 분이 확정되고 14:30 스냅샷 기준시각에 해당해요.
     * 기대: 두 종목의 1분 집계가 모두 저장되고, 005930은 스냅샷 단계까지 진행하며, 묶음 오류 없이
     *       건너뛴 스냅샷 1건이 상태에 남아요. 다음 flush에서 같은 분을 다시 저장하지 않아요(확인 처리됨).
     */
    @Test
    void oneSymbolFailureDoesNotRollBackTheBatch() {
        TrajectoryProperties policy = mock(TrajectoryProperties.class);
        when(policy.enabled()).thenReturn(true);
        when(policy.watermark()).thenReturn(Duration.ofSeconds(2));
        when(policy.analysisStart()).thenReturn(LocalTime.of(14, 30));
        when(policy.analysisEnd()).thenReturn(LocalTime.of(15, 20));
        when(policy.snapshotMinutes()).thenReturn(5);
        ClosingTradingCalendar calendar = mock(ClosingTradingCalendar.class);
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(calendar.now()).thenReturn(Instant.parse("2026-09-30T05:30:03Z"));
        TrajectoryStore store = mock(TrajectoryStore.class);
        StockRepository stocks = mock(StockRepository.class);
        when(stocks.findByStockCode("BAD")).thenThrow(new IllegalStateException("broken row"));
        TrajectoryService service = new TrajectoryService(policy, store, stocks, mock(StockCandleRepository.class),
                calendar, mock(PlatformTransactionManager.class), mock(MicrostructureService.class));

        service.receive(tick("BAD", "05:29:01"));
        service.receive(tick("005930", "05:29:02"));
        service.flush();

        verify(store, times(2)).minute(any());
        verify(stocks).findByStockCode("005930");
        assertThat(service.status()).containsEntry("skippedSnapshots", 1L)
                .containsEntry("lastSkippedSnapshot", "BAD@2026-09-30T05:30:00Z");
        assertThat(service.status().get("error")).isNull();

        service.flush();
        verify(store, times(2)).minute(any());
    }

    /** 체결시각=수신시각인 단순 틱을 만드는 도우미예요. */
    private ObservedMarketTick tick(String code, String time) {
        Instant at = Instant.parse("2026-09-30T" + time + "Z");
        var tick = new MarketTick(code, LocalDate.of(2026, 9, 30), at, BigDecimal.valueOf(100), 10,
                10, BigDecimal.valueOf(1000), 1, BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                BigDecimal.valueOf(100), BigDecimal.valueOf(120), 5L, 5L, null, false, null, null);
        return new ObservedMarketTick(tick, null, at);
    }
}
