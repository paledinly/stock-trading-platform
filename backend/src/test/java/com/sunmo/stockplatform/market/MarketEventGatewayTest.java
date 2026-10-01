package com.sunmo.stockplatform.market;

import com.sunmo.stockplatform.market.application.MarketEventGateway;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketEventGatewayTest {
    @Test void disconnectedClientDoesNotInterruptOtherClientsOrCompleteAgain() throws Exception {
        verifyFailedClient(new IOException("현재 연결은 사용자의 호스트 시스템의 소프트웨어의 의해 중단되었습니다"));
    }

    @Test void alreadyCompletedClientIsRemovedWithoutCompletingAgain() throws Exception {
        verifyFailedClient(new IllegalStateException("ResponseBodyEmitter has already completed"));
    }

    @SuppressWarnings("unchecked")
    private void verifyFailedClient(Exception failure) throws Exception {
        var settings = mock(RealtimeMarketProperties.class);
        when(settings.replaySize()).thenReturn(10);
        var gateway = new MarketEventGateway(settings);
        var closed = mock(SseEmitter.class);
        var healthy = mock(SseEmitter.class);
        doThrow(failure).when(closed).send(any(SseEmitter.SseEventBuilder.class));
        var clients = (ConcurrentMap<String, SseEmitter>) ReflectionTestUtils.getField(gateway, "clients");
        clients.put("closed", closed); clients.put("healthy", healthy);

        assertThatCode(() -> gateway.publish("quote.updated", Map.of("stockCode", "005930"))).doesNotThrowAnyException();
        gateway.publish("quote.updated", Map.of("stockCode", "005930"));

        assertThat(clients).containsOnlyKeys("healthy");
        verify(closed, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(closed, never()).complete();
        verify(closed, never()).completeWithError(any());
        verify(healthy, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }
}
