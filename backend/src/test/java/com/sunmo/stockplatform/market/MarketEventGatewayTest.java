package com.sunmo.stockplatform.market;

import com.sunmo.stockplatform.market.application.MarketEventGateway;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketEventGatewayTest {
    @Test void disconnectedClientDoesNotInterruptOtherClientsOrCompleteAgain() throws Exception {
        verifyFailedClient(new IOException("현재 연결은 사용자의 호스트 시스템의 소프트웨어의 의해 중단되었습니다"));
    }

    @Test void alreadyCompletedClientIsRemovedWithoutCompletingAgain() throws Exception {
        verifyFailedClient(new IllegalStateException("ResponseBodyEmitter has already completed"));
    }

    private void verifyFailedClient(Exception failure) throws Exception {
        var gateway = gateway();
        var closed = mock(SseEmitter.class);
        var healthy = mock(SseEmitter.class);
        doThrow(failure).when(closed).send(any(SseEmitter.SseEventBuilder.class));
        gateway.register(closed, null);
        gateway.register(healthy, null);

        assertThatCode(() -> gateway.publish("quote.updated", Map.of("stockCode", "005930"))).doesNotThrowAnyException();
        verify(closed, timeout(2000)).send(any(SseEmitter.SseEventBuilder.class));
        awaitClients(gateway, 1);
        gateway.publish("quote.updated", Map.of("stockCode", "005930"));

        verify(healthy, timeout(2000).times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(closed, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(closed, never()).complete();
        verify(closed, never()).completeWithError(any());
    }

    @Test void blockedClientNeverStallsPublisherAndIsDisconnectedOnOverflow() throws Exception {
        var gateway = gateway();
        var release = new CountDownLatch(1);
        var stuck = mock(SseEmitter.class);
        var healthy = mock(SseEmitter.class);
        doAnswer(ignored -> { release.await(); return null; }).when(stuck).send(any(SseEmitter.SseEventBuilder.class));
        gateway.register(stuck, null);
        gateway.register(healthy, null);

        int half = MarketEventGateway.CLIENT_QUEUE_CAPACITY / 2;
        int events = MarketEventGateway.CLIENT_QUEUE_CAPACITY + 10;
        long started = System.nanoTime();
        for (int i = 0; i < half; i++) gateway.publish("quote.updated", Map.of("seq", i));
        long publishing = System.nanoTime() - started;
        // Let the healthy client catch up so only the stuck one can overflow.
        verify(healthy, timeout(5000).times(half)).send(any(SseEmitter.SseEventBuilder.class));
        started = System.nanoTime();
        for (int i = half; i < events; i++) gateway.publish("quote.updated", Map.of("seq", i));
        publishing += System.nanoTime() - started;
        assertThat(Duration.ofNanos(publishing)).isLessThan(Duration.ofSeconds(2));

        assertThat(gateway.slowClientDisconnects()).isEqualTo(1);
        assertThat(gateway.clientCount()).isEqualTo(1);
        verify(healthy, timeout(5000).times(events)).send(any(SseEmitter.SseEventBuilder.class));
        release.countDown();
        // The overflowed client is ended by its own sender, so the browser reconnects with Last-Event-ID.
        verify(stuck, timeout(2000)).complete();
    }

    @Test void reconnectReplaysOnlyEventsAfterLastId() throws Exception {
        var gateway = gateway();
        var first = gateway.publish("quote.updated", Map.of("seq", 1));
        gateway.publish("quote.updated", Map.of("seq", 2));
        gateway.publish("quote.updated", Map.of("seq", 3));
        var resumed = mock(SseEmitter.class);

        gateway.register(resumed, first.id());

        verify(resumed, timeout(2000).times(2)).send(any(SseEmitter.SseEventBuilder.class));
        TimeUnit.MILLISECONDS.sleep(100);
        verify(resumed, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    private static MarketEventGateway gateway() {
        var settings = mock(RealtimeMarketProperties.class);
        when(settings.replaySize()).thenReturn(10);
        return new MarketEventGateway(settings);
    }

    private static void awaitClients(MarketEventGateway gateway, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (gateway.clientCount() != expected && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(5);
        assertThat(gateway.clientCount()).isEqualTo(expected);
    }
}
