/*
 * RealtimeTickProcessor(실시간 틱을 별도 작업 스레드에서 처리하는 대기열)가 순서·수신시각을 지키고,
 * 대기열이 넘칠 때 버린 틱을 다음 틱에 손실 표시로 남기는지 확인해요.
 * 웹소켓 수신 스레드가 막히거나 손실이 숨겨지면 5분봉 신뢰도가 떨어지므로 중요해요.
 */
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

/** 실시간 틱 처리 대기열의 순서·손실 표시를 점검하는 테스트예요. */
class RealtimeTickProcessorTest {
    /** 순번만 다른 005930 테스트 틱을 만드는 도우미예요. */
    private static MarketTick tick(long sequence) {
        return new MarketTick("005930", LocalDate.of(2026,10,6), Instant.parse("2026-10-06T00:00:00Z"),
                BigDecimal.TEN, 1, sequence, BigDecimal.TEN.multiply(BigDecimal.valueOf(sequence)), sequence);
    }

    /**
     * 크기 1 대기열이 호출 스레드에서 처리하지 않고, 수신 시각과 순서를 지키며, 넘친 틱 뒤에 손실을 표시하는지 확인해요.
     * 준비: 목(mock, 가짜 객체) 서비스가 1번 틱에서 멈추게 한 뒤 2, 3번을 넣어 3번이 버려지게 하고, 풀어 준 뒤 4번을 넣어요.
     * 기대: 처리 순서 1, 2, 4이고 2번은 손실 없음, 4번은 손실 표시(true)이며 대기 1·버림 1과 처리 시간이 기록돼요.
     */
    @Test void boundedQueueNeverRunsOnCallerPreservesReceiptAndOrderAndMarksLoss() throws Exception {
        // 준비: 1번 틱 처리 중에 멈춰 두기 위한 래치(CountDownLatch, 스레드 신호용 카운터)들이에요.
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
        // 실행: 1번이 처리 중일 때 2번은 대기열에, 3번은 넘쳐서 버려져요.
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
        // 확인
        assertThat(order).containsExactly(1L,2L,4L);
        verify(market).onTick(eq(tick(2)),eq(receipt),eq(false));
        verify(market).onTick(eq(tick(4)),eq(receipt),eq(true));
        assertThat(diagnostics.snapshot().timings().get("sourceToCallback").averageMs()).isEqualTo(1000);
        assertThat(diagnostics.snapshot().timings().get("tickTransaction").count()).isEqualTo(3);
    }

    /**
     * 실패한 틱 뒤에 이미 대기 중인 틱은 손실 표시를 받고, 처리 작업은 계속되는지 확인해요.
     * 준비: 1번 틱 처리에서 예외가 나게 하고 1, 2번을 연달아 넣어요.
     * 기대: 2번은 손실 표시(true)로 처리되고 처리 오류 1건, 파싱 오류 0건이에요.
     */
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

    /**
     * 재연결(끊김) 뒤 종목별 다음 틱에 손실 표시를 하는지 확인해요.
     * 준비: 1번 틱 → 연결 끊김 → 2번 틱 순서로 넣어요.
     * 기대: 1번은 false, 2번은 true로 처리돼요.
     */
    @Test void reconnectMarksNextInputPerSymbol() {
        var market=mock(MarketDataService.class);
        try(var worker=new RealtimeTickProcessor(market,new RealtimeDiagnostics(),4)) {
            worker.submit(tick(1),Instant.now()); worker.disconnected(); worker.submit(tick(2),Instant.now());
        }
        verify(market).onTick(eq(tick(1)),any(),eq(false));
        verify(market).onTick(eq(tick(2)),any(),eq(true));
    }
}
