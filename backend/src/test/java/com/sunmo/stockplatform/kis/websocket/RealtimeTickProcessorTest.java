package com.sunmo.stockplatform.kis.websocket;

import com.sunmo.stockplatform.market.application.*;
import com.sunmo.stockplatform.market.domain.MarketTick;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RealtimeTickProcessorTest {
    private static MarketTick tick(long sequence) {
        return new MarketTick("005930", LocalDate.of(2026,10,6), Instant.parse("2026-10-06T00:00:00Z"),
                BigDecimal.TEN, 1, sequence, BigDecimal.TEN.multiply(BigDecimal.valueOf(sequence)), sequence);
    }

    @Test void boundedQueueNeverRunsOnCallerPreservesReceiptAndOrderAndMarksLoss() throws Exception {
        var market = mock(MarketDataService.class);
        var diagnostics = new RealtimeDiagnostics();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var processed = new CountDownLatch(2);
        List<Long> order = new CopyOnWriteArrayList<>();
        Instant receipt = Instant.parse("2026-10-06T00:00:01Z");
        when(market.onTick(any(), any(), anyBoolean())).thenAnswer(call -> {
            MarketTick t = call.getArgument(0);
            assertThat(Thread.currentThread().getName()).isEqualTo("kis-tick-processing");
            assertThat((Instant)call.getArgument(1)).isEqualTo(receipt);
            if(t.sequence()==1) { entered.countDown(); assertThat(release.await(3,TimeUnit.SECONDS)).isTrue(); }
            order.add(t.sequence()); processed.countDown(); return 0L;
        });
        var worker = new RealtimeTickProcessor(market, diagnostics, 1);
        try {
            worker.submit(tick(1),receipt);
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            worker.submit(tick(2),receipt);
            worker.submit(tick(3),receipt);
            assertThat(diagnostics.snapshot().processingQueued()).isEqualTo(1);
            assertThat(diagnostics.snapshot().processingDropped()).isEqualTo(1);
            release.countDown(); assertThat(processed.await(3,TimeUnit.SECONDS)).isTrue();
            worker.submit(tick(4),receipt);
        } finally { release.countDown(); worker.close(); }
        assertThat(order).containsExactly(1L,2L,4L);
        verify(market).onTick(eq(tick(2)),eq(receipt),eq(false));
        verify(market).onTick(eq(tick(4)),eq(receipt),eq(true));
        assertThat(diagnostics.snapshot().timings().get("sourceToCallback").averageMs()).isEqualTo(1000);
        assertThat(diagnostics.snapshot().timings().get("tickTransaction").count()).isEqualTo(3);
    }

    @Test void failedTickMarksAlreadyQueuedSuccessorAndConsumerContinues() {
        var market = mock(MarketDataService.class); var diagnostics = new RealtimeDiagnostics();
        when(market.onTick(eq(tick(1)),any(),anyBoolean())).thenThrow(new IllegalStateException("test"));
        try(var worker = new RealtimeTickProcessor(market,diagnostics,4)) {
            worker.submit(tick(1),Instant.now()); worker.submit(tick(2),Instant.now());
        }
        verify(market).onTick(eq(tick(2)),any(),eq(true));
        assertThat(diagnostics.snapshot().processingErrors()).isEqualTo(1);
        assertThat(diagnostics.snapshot().parseErrors()).isZero();
    }

    @Test void reconnectMarksNextInputPerSymbol() {
        var market=mock(MarketDataService.class);
        try(var worker=new RealtimeTickProcessor(market,new RealtimeDiagnostics(),4)) {
            worker.submit(tick(1),Instant.now()); worker.disconnected(); worker.submit(tick(2),Instant.now());
        }
        verify(market).onTick(eq(tick(1)),any(),eq(false));
        verify(market).onTick(eq(tick(2)),any(),eq(true));
    }
}
