package com.sunmo.stockplatform.kis.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.market.application.MarketDataService;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import com.sunmo.stockplatform.marketwide.application.MarketSessionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KisRealtimeClientTest {
    @Test
    void reconnectsWhenTheSocketLooksConnectedButMessagesAreStale() {
        RealtimeDiagnostics diagnostics = mock(RealtimeDiagnostics.class);
        MarketSessionPolicy session = mock(MarketSessionPolicy.class);
        when(session.evaluate(any())).thenReturn(new MarketSessionPolicy.Decision(true, Instant.now(), "ELIGIBLE"));
        when(diagnostics.isConnectionStale(any(), eq(Duration.ofSeconds(60)))).thenReturn(true);
        WebSocket socket = mock(WebSocket.class);
        KisRealtimeClient client = client(diagnostics, session);
        ReflectionTestUtils.setField(client, "socket", socket);

        client.reconnectIfStale();

        verify(socket).abort();
        verify(diagnostics).disconnected();
        client.shutdown();
    }

    @Test
    void reconnectsAfterAWebSocketNormalClose() {
        RealtimeDiagnostics diagnostics = new RealtimeDiagnostics();
        diagnostics.connected();
        KisRealtimeClient client = client(diagnostics, mock(MarketSessionPolicy.class));

        client.onClose(mock(WebSocket.class), WebSocket.NORMAL_CLOSURE, "remote close");

        assertThat(diagnostics.snapshot().connected()).isFalse();
        client.shutdown();
    }

    private KisRealtimeClient client(RealtimeDiagnostics diagnostics, MarketSessionPolicy session) {
        return new KisRealtimeClient(mock(KisApprovalClient.class), mock(KisRealtimeTickParser.class),
                mock(MarketDataService.class), mock(RealtimeSubscriptionRegistry.class),
                new RealtimeMarketProperties(true, URI.create("ws://localhost"), Duration.ZERO,
                        Duration.ofHours(1), 10, 41, Duration.ofSeconds(60)),
                diagnostics, new ObjectMapper(), session);
    }
}
