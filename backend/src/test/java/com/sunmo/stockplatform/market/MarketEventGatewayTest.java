/*
 * MarketEventGateway(브라우저들에 SSE 이벤트를 나눠 보내는 중계기)가 문제 있는 클라이언트를 안전하게 정리하는지 확인해요.
 * 한 클라이언트가 끊기거나 느려도 다른 클라이언트와 발행자가 멈추지 않아야 실시간 화면이 유지돼요.
 */
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

/** SSE 이벤트 중계기의 클라이언트 격리·재전송을 점검하는 테스트예요. */
class MarketEventGatewayTest {
    /**
     * 끊긴 클라이언트(IOException)가 다른 클라이언트를 방해하지 않고, 다시 complete되지 않는지 확인해요.
     * 준비/기대는 verifyFailedClient와 같아요.
     */
    @Test void disconnectedClientDoesNotInterruptOtherClientsOrCompleteAgain() throws Exception {
        verifyFailedClient(new IOException("현재 연결은 사용자의 호스트 시스템의 소프트웨어의 의해 중단되었습니다"));
    }

    /**
     * 이미 완료된 클라이언트(IllegalStateException)는 다시 완료하지 않고 목록에서 빠지는지 확인해요.
     * 준비/기대는 verifyFailedClient와 같아요.
     */
    @Test void alreadyCompletedClientIsRemovedWithoutCompletingAgain() throws Exception {
        verifyFailedClient(new IllegalStateException("ResponseBodyEmitter has already completed"));
    }

    /**
     * 실패하는 클라이언트 처리의 공통 검증 도우미예요.
     * 준비: 목(mock, 가짜 객체) SSE 연결 2개 중 하나는 보낼 때 지정한 예외를 던지게 해요.
     * 기대: 발행은 예외 없이 끝나고, 실패한 쪽은 1번만 시도 후 제거되며(complete 호출 없음), 정상 쪽은 2번 모두 받아요.
     */
    private void verifyFailedClient(Exception failure) throws Exception {
        // 준비
        var gateway = gateway();
        var closed = mock(SseEmitter.class);
        var healthy = mock(SseEmitter.class);
        doThrow(failure).when(closed).send(any(SseEmitter.SseEventBuilder.class));
        gateway.register(closed, null);
        gateway.register(healthy, null);

        // 실행 및 확인
        assertThatCode(() -> gateway.publish("quote.updated", Map.of("stockCode", "005930"))).doesNotThrowAnyException();
        verify(closed, timeout(2000)).send(any(SseEmitter.SseEventBuilder.class));
        awaitClients(gateway, 1);
        gateway.publish("quote.updated", Map.of("stockCode", "005930"));

        verify(healthy, timeout(2000).times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(closed, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(closed, never()).complete();
        verify(closed, never()).completeWithError(any());
    }

    /**
     * 막힌 클라이언트가 발행자를 멈추지 않고, 대기열이 넘치면 끊기는지 확인해요.
     * 준비: 한 연결은 보내기에서 무한히 기다리게 하고, 대기열 용량+10개 이벤트를 발행해요.
     * 기대: 발행 시간 2초 미만, 느린 클라이언트 끊김 1건, 남은 클라이언트 1개, 정상 쪽은 모두 받고 막힌 쪽은 스스로 complete돼요.
     * ⚠️ 확인 필요: 실제 시간(2초, 5초 대기)에 기대는 검증이라 아주 느린 CI 환경에서는 가끔 실패할 수 있어 보여요.
     */
    @Test void blockedClientNeverStallsPublisherAndIsDisconnectedOnOverflow() throws Exception {
        // 준비
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

    /**
     * 재연결 시 마지막으로 받은 이벤트 ID 이후 것만 다시 보내는지 확인해요.
     * 준비: 이벤트 3개를 발행한 뒤 첫 번째 ID로 새 연결을 등록해요.
     * 기대: 2번째·3번째 두 건만 보내고, 100ms 더 기다려도 추가 전송이 없어요.
     */
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

    /** 재전송 버퍼 크기 10인 테스트용 중계기를 만드는 도우미예요. */
    private static MarketEventGateway gateway() {
        var settings = mock(RealtimeMarketProperties.class);
        when(settings.replaySize()).thenReturn(10);
        return new MarketEventGateway(settings);
    }

    /** 연결된 클라이언트 수가 기대값이 될 때까지 최대 2초 기다리는 도우미예요. */
    private static void awaitClients(MarketEventGateway gateway, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (gateway.clientCount() != expected && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(5);
        assertThat(gateway.clientCount()).isEqualTo(expected);
    }
}
