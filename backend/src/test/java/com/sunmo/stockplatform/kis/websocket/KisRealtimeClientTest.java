package com.sunmo.stockplatform.kis.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.market.application.MarketDataService;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import com.sunmo.stockplatform.market.application.RealtimeSessionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KisRealtimeClientTest {
    @Test
    void outsideSessionDoesNotIssueApprovalAndClosesExistingSocketWithoutRetry() {
        var approval = mock(KisApprovalClient.class);
        var session = mock(RealtimeSessionPolicy.class);
        var diagnostics = mock(RealtimeDiagnostics.class);
        var registry = mock(RealtimeSubscriptionRegistry.class);
        var client = new KisRealtimeClient(approval, mock(KisRealtimeTickParser.class), mock(MarketDataService.class),
                registry, new RealtimeMarketProperties(true, URI.create("ws://localhost"), Duration.ZERO,
                Duration.ofHours(1), 10, 41, Duration.ofSeconds(60)), diagnostics, new ObjectMapper(), session);
        try {
            client.run(null);
            var socket = mock(WebSocket.class);
            ReflectionTestUtils.setField(client, "socket", socket);
            client.reconnectIfStale();
            verify(socket).abort();
            verify(diagnostics).disconnected();
            // A callback from the deliberately closed old socket must not restart collection.
            client.onClose(socket, WebSocket.NORMAL_CLOSURE, "session ended");
            client.onError(socket, new IllegalStateException("old socket"));
            verifyNoInteractions(approval);
            assertThat(((java.util.concurrent.atomic.AtomicBoolean) ReflectionTestUtils.getField(client, "reconnectScheduled")).get()).isFalse();
            verify(registry, never()).remove(anyString(), any());
        } finally { client.shutdown(); }
    }

    @Test
    void periodicCheckRestartsConnectionWhenNextSessionOpens() {
        var session = mock(RealtimeSessionPolicy.class);
        var approval = mock(KisApprovalClient.class);
        // Stop before making a network request while verifying that opening triggers connection.
        when(approval.issue()).thenThrow(new IllegalStateException("test unavailable"));
        var client = new KisRealtimeClient(approval, mock(KisRealtimeTickParser.class), mock(MarketDataService.class),
                mock(RealtimeSubscriptionRegistry.class), new RealtimeMarketProperties(true, URI.create("ws://localhost"),
                Duration.ZERO, Duration.ofHours(1), 10, 41, Duration.ofSeconds(60)), mock(RealtimeDiagnostics.class), new ObjectMapper(), session);
        try {
            client.reconnectIfStale();
            verifyNoInteractions(approval);
            when(session.connectionAllowed()).thenReturn(true);
            client.reconnectIfStale();
            verify(approval).issue();
        } finally { client.shutdown(); }
    }

    @Test
    void reconnectsWhenTheSocketLooksConnectedButMessagesAreStale() {
        RealtimeDiagnostics diagnostics = mock(RealtimeDiagnostics.class);
        RealtimeSessionPolicy session = mock(RealtimeSessionPolicy.class);
        when(session.connectionAllowed()).thenReturn(true);
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
        var session = mock(RealtimeSessionPolicy.class);
        when(session.connectionAllowed()).thenReturn(true);
        KisRealtimeClient client = client(diagnostics, session);

        WebSocket socket = mock(WebSocket.class);
        ReflectionTestUtils.setField(client, "socket", socket);
        client.onClose(socket, WebSocket.NORMAL_CLOSURE, "remote close");

        assertThat(diagnostics.snapshot().connected()).isFalse();
        assertThat(((java.util.concurrent.atomic.AtomicBoolean) ReflectionTestUtils.getField(client, "reconnectScheduled")).get()).isTrue();
        client.shutdown();
    }

    @Test
    void processingFailureIsNotCountedAsAParseFailure() {
        RealtimeDiagnostics diagnostics = new RealtimeDiagnostics();
        KisRealtimeTickParser parser = mock(KisRealtimeTickParser.class);
        MarketDataService market = mock(MarketDataService.class);
        var tick = new com.sunmo.stockplatform.market.domain.MarketTick("005930", LocalDate.of(2026, 9, 23),
                Instant.parse("2026-09-23T06:00:00Z"), new BigDecimal("100"), 1, 1,
                new BigDecimal("100"), 1);
        when(parser.parseMany("payload", 1)).thenReturn(List.of(tick));
        doThrow(new IllegalStateException("storage unavailable")).when(market).onTick(tick);
        KisRealtimeClient client = new KisRealtimeClient(mock(KisApprovalClient.class), parser, market,
                mock(RealtimeSubscriptionRegistry.class),
                new RealtimeMarketProperties(true, URI.create("ws://localhost"), Duration.ZERO,
                        Duration.ofHours(1), 10, 41, Duration.ofSeconds(60)),
                diagnostics, new ObjectMapper(), acceptingSession());

        ReflectionTestUtils.invokeMethod(client, "handle", mock(WebSocket.class), "0|H0STCNT0|1|payload");

        assertThat(diagnostics.snapshot().parseErrors()).isZero();
        assertThat(diagnostics.snapshot().processingErrors()).isEqualTo(1);
        client.shutdown();
    }

    private RealtimeSessionPolicy acceptingSession() {
        var session = mock(RealtimeSessionPolicy.class);
        when(session.accepts(any())).thenReturn(true);
        return session;
    }

    private KisRealtimeClient client(RealtimeDiagnostics diagnostics, RealtimeSessionPolicy session) {
        return new KisRealtimeClient(mock(KisApprovalClient.class), mock(KisRealtimeTickParser.class),
                mock(MarketDataService.class), mock(RealtimeSubscriptionRegistry.class),
                new RealtimeMarketProperties(true, URI.create("ws://localhost"), Duration.ZERO,
                        Duration.ofHours(1), 10, 41, Duration.ofSeconds(60)),
                diagnostics, new ObjectMapper(), session);
    }
}
