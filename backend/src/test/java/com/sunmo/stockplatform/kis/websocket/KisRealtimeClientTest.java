/*
 * KisRealtimeClient(KIS 실시간 웹소켓 연결을 관리하는 클라이언트)의 수신·재연결·세션 경계 동작을 확인해요.
 * 수신 시각을 잘못 기록하거나, 장 밖에서 연결을 다시 열거나, 끊긴 연결을 방치하면 실시간 데이터가 비거나 왜곡돼요.
 * 내부 필드는 ReflectionTestUtils(테스트에서 private 필드·메서드에 접근하는 도구)로 직접 읽고 바꿔요.
 */
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

/** 실시간 웹소켓 클라이언트의 수신·재연결 규칙을 점검하는 테스트예요. */
class KisRealtimeClientTest {
    /**
     * 한 메시지가 여러 조각(fragment)으로 나뉘어 와도 첫 조각을 받은 시각을 수신 시각으로 쓰는지 확인해요.
     * 준비: 목(mock, 가짜 객체) 파서·서비스·세션 정책을 두고, 머리 조각과 "payload" 조각을 차례로 보내요.
     * 기대: 틱이 첫 조각 시각(frameReceivedAt)으로 전달되고, 소켓에 다음 메시지 요청이 3번(열기 1 + 조각 2) 가요.
     */
    @Test void fragmentedFrameRetainsFirstCallbackTime() {
        var parser = mock(KisRealtimeTickParser.class);
        var market = mock(MarketDataService.class);
        var session = acceptingSession(); when(session.connectionAllowed()).thenReturn(true);
        var tick = new com.sunmo.stockplatform.market.domain.MarketTick("005930", LocalDate.of(2026,10,6),
                Instant.parse("2026-10-06T00:00:00Z"), BigDecimal.TEN,1,1,BigDecimal.TEN,1);
        when(parser.parseMany("payload",1)).thenReturn(List.of(tick));
        var client = new KisRealtimeClient(mock(KisApprovalClient.class),parser,market,
                mock(RealtimeSubscriptionRegistry.class),new RealtimeMarketProperties(true,URI.create("ws://localhost"),
                Duration.ZERO,Duration.ofHours(1),10,41,Duration.ofSeconds(60)),new RealtimeDiagnostics(),new ObjectMapper(),session);
        var socket = mock(WebSocket.class); client.onOpen(socket);
        client.onText(socket,"0|H0STCNT0|1|",false);
        Instant receipt = (Instant)ReflectionTestUtils.getField(client,"frameReceivedAt");
        client.onText(socket,"payload",true);
        client.shutdown();
        verify(market).onTick(eq(tick),eq(receipt),eq(false));
        verify(socket,times(3)).request(1);
    }
    /**
     * 장 시간 밖이면 접속 승인키를 받지 않고, 기존 소켓은 닫되 재시도하지 않는지 확인해요.
     * 준비: connectionAllowed가 false(목 기본값)인 세션 정책으로 실행하고, 기존 소켓을 넣은 뒤 끊김 콜백을 흉내 내요.
     * 기대: 소켓 abort·끊김 기록이 일어나고, 승인 요청 없음, 재연결 예약 없음, 구독 해제 없음이에요.
     */
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

    /**
     * 정기 점검에서 다음 세션이 열리면 연결을 다시 시작하는지 확인해요.
     * 준비: 처음엔 연결 불가, 그다음 연결 가능으로 바꿔 두 번 점검해요(승인 요청은 네트워크 전에 예외로 멈춰요).
     * 기대: 첫 점검엔 승인 요청이 없고, 두 번째에 승인 요청이 1번 일어나요.
     */
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

    /**
     * 소켓이 연결된 것처럼 보여도 메시지가 끊겼으면(stale) 다시 연결하는지 확인해요.
     * 준비: 진단 목이 stale을 true로 돌려주게 하고 기존 소켓을 넣어요.
     * 기대: 소켓 abort와 끊김 기록이 일어나요.
     */
    @Test
    void reconnectsWhenTheSocketLooksConnectedButMessagesAreStale() {
        // 준비
        RealtimeDiagnostics diagnostics = mock(RealtimeDiagnostics.class);
        RealtimeSessionPolicy session = mock(RealtimeSessionPolicy.class);
        when(session.connectionAllowed()).thenReturn(true);
        when(diagnostics.isConnectionStale(any(), eq(Duration.ofSeconds(60)))).thenReturn(true);
        WebSocket socket = mock(WebSocket.class);
        KisRealtimeClient client = client(diagnostics, session);
        ReflectionTestUtils.setField(client, "socket", socket);

        // 실행
        client.reconnectIfStale();

        // 확인
        verify(socket).abort();
        verify(diagnostics).disconnected();
        client.shutdown();
    }

    /**
     * 서버가 정상 종료(NORMAL_CLOSURE)로 끊어도 재연결을 예약하는지 확인해요.
     * 준비: 연결된 진단 정보와 연결 가능 세션에서 현재 소켓의 onClose를 호출해요.
     * 기대: 연결 상태가 false가 되고 reconnectScheduled가 true예요.
     */
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

    /**
     * 틱 처리(저장) 실패를 파싱 실패로 세지 않는지 확인해요.
     * 준비: 파싱은 성공하고 시장 데이터 서비스가 "storage unavailable" 예외를 던지게 해요.
     * 기대: 파싱 오류 0, 처리 오류 1이에요.
     * ⚠️ 확인 필요: client.shutdown()이 두 번 호출돼요. 중복 호출이 무해하다면 문제는 없지만 의도한 것인지 확인이 필요해 보여요.
     */
    @Test
    void processingFailureIsNotCountedAsAParseFailure() {
        RealtimeDiagnostics diagnostics = new RealtimeDiagnostics();
        KisRealtimeTickParser parser = mock(KisRealtimeTickParser.class);
        MarketDataService market = mock(MarketDataService.class);
        var tick = new com.sunmo.stockplatform.market.domain.MarketTick("005930", LocalDate.of(2026, 9, 23),
                Instant.parse("2026-09-23T06:00:00Z"), new BigDecimal("100"), 1, 1,
                new BigDecimal("100"), 1);
        when(parser.parseMany("payload", 1)).thenReturn(List.of(tick));
        doThrow(new IllegalStateException("storage unavailable")).when(market).onTick(eq(tick), any(), anyBoolean());
        KisRealtimeClient client = new KisRealtimeClient(mock(KisApprovalClient.class), parser, market,
                mock(RealtimeSubscriptionRegistry.class),
                new RealtimeMarketProperties(true, URI.create("ws://localhost"), Duration.ZERO,
                        Duration.ofHours(1), 10, 41, Duration.ofSeconds(60)),
                diagnostics, new ObjectMapper(), acceptingSession());

        ReflectionTestUtils.invokeMethod(client, "handle", mock(WebSocket.class), "0|H0STCNT0|1|payload");
        client.shutdown();

        assertThat(diagnostics.snapshot().parseErrors()).isZero();
        assertThat(diagnostics.snapshot().processingErrors()).isEqualTo(1);
        client.shutdown();
    }

    /**
     * 연결이 없을 때 해지 요청은 종목을 "대기 중"으로 되살리지 않고 구독 상태에서 지우는지 확인해요.
     * 준비: 소켓 없이 005930 구독 요청과 000660 구독 요청을 보내고, 000660만 해지 요청을 보내요.
     * 기대: 005930은 QUEUED로 남고 000660은 상태 목록에서 사라져요.
     */
    @Test
    void unsubscribeWithoutSocketRemovesStatusInsteadOfQueueing() {
        RealtimeDiagnostics diagnostics = new RealtimeDiagnostics();
        KisRealtimeClient client = client(diagnostics, mock(RealtimeSessionPolicy.class));
        try {
            ReflectionTestUtils.invokeMethod(client, "send", "005930", true);
            ReflectionTestUtils.invokeMethod(client, "send", "000660", true);
            ReflectionTestUtils.invokeMethod(client, "send", "000660", false);

            var statuses = diagnostics.snapshot().subscriptions();
            assertThat(statuses).containsOnlyKeys("005930");
            assertThat(statuses.get("005930").state()).isEqualTo(RealtimeDiagnostics.SubscriptionState.QUEUED);
        } finally { client.shutdown(); }
    }

    /**
     * 기동 작업(run) 전에 시장 스캔이 구독을 추가해도 요청 경로에 올라가는지 봐요.
     * 2026-10-15 13:51 장중 재시작에서 run 전에 추가된 정밀 31종목이 요청 없이 목록에만 남았어요.
     */
    @Test
    void subscriptionsAddedBeforeTheRunnerStillReachTheSendPath() {
        RealtimeDiagnostics diagnostics = new RealtimeDiagnostics();
        var properties = new RealtimeMarketProperties(true, URI.create("ws://localhost"), Duration.ZERO,
                Duration.ofHours(1), 10, 41, Duration.ofSeconds(60));
        var registry = new RealtimeSubscriptionRegistry(properties);
        KisRealtimeClient client = new KisRealtimeClient(mock(KisApprovalClient.class), mock(KisRealtimeTickParser.class),
                mock(MarketDataService.class), registry, properties, diagnostics, new ObjectMapper(),
                mock(RealtimeSessionPolicy.class));
        try {
            ReflectionTestUtils.invokeMethod(client, "listenToSubscriptionChanges");
            registry.add("005930", RealtimeSubscriptionRegistry.Source.PRECISION);

            // 연결 전이라 실제 전송 대신 대기(QUEUED)로 남지만, 변경 알림을 받았다는 증거예요(연결되면 전체 재요청 대상).
            assertThat(diagnostics.snapshot().subscriptions()).containsOnlyKeys("005930");
        } finally { client.shutdown(); }
    }

    /**
     * 구독 요청에 KIS가 "이미 구독 중"(ALREADY IN SUBSCRIBE)으로 답하면 성공으로 처리하는지 봐요(2026-10-23 011330).
     * 기대: 등록부에 성공으로 알리고, 실시간 수신 장부에도 구독 성공을 알려요. 다른 실패 코드는 그대로 실패예요.
     */
    @Test
    void alreadySubscribedResponseCountsAsAccepted() {
        var registry = mock(RealtimeSubscriptionRegistry.class);
        var market = mock(MarketDataService.class);
        var session = mock(RealtimeSessionPolicy.class);
        when(session.connectionAllowed()).thenReturn(true);
        KisRealtimeClient client = new KisRealtimeClient(mock(KisApprovalClient.class), mock(KisRealtimeTickParser.class),
                market, registry, new RealtimeMarketProperties(true, URI.create("ws://localhost"), Duration.ZERO,
                Duration.ofHours(1), 10, 41, Duration.ofSeconds(60)), new RealtimeDiagnostics(), new ObjectMapper(), session);
        try {
            var socket = mock(WebSocket.class);
            client.onOpen(socket);
            client.onText(socket, "{\"header\":{\"tr_id\":\"H0STCNT0\",\"tr_key\":\"011330\"},"
                    + "\"body\":{\"rt_cd\":\"1\",\"msg1\":\"ALREADY IN SUBSCRIBE\"}}", true);
            client.onText(socket, "{\"header\":{\"tr_id\":\"H0STCNT0\",\"tr_key\":\"005930\"},"
                    + "\"body\":{\"rt_cd\":\"1\",\"msg1\":\"MAX SUBSCRIBE OVER\"}}", true);
            verify(registry).acknowledge("011330", true, true, "ALREADY IN SUBSCRIBE");
            verify(market).realtimeSubscribed(eq("011330"), any());
            verify(registry).acknowledge("005930", true, false, "MAX SUBSCRIBE OVER");
            verify(market, never()).realtimeSubscribed(eq("005930"), any());
        } finally { client.shutdown(); }
    }

    /** 모든 틱을 받아들이는 세션 정책 목을 만드는 도우미예요. */
    private RealtimeSessionPolicy acceptingSession() {
        var session = mock(RealtimeSessionPolicy.class);
        when(session.accepts(any())).thenReturn(true);
        return session;
    }

    /** 진단 정보와 세션 정책만 바꾼 실시간 클라이언트를 만드는 도우미예요. */
    private KisRealtimeClient client(RealtimeDiagnostics diagnostics, RealtimeSessionPolicy session) {
        return new KisRealtimeClient(mock(KisApprovalClient.class), mock(KisRealtimeTickParser.class),
                mock(MarketDataService.class), mock(RealtimeSubscriptionRegistry.class),
                new RealtimeMarketProperties(true, URI.create("ws://localhost"), Duration.ZERO,
                        Duration.ofHours(1), 10, 41, Duration.ofSeconds(60)),
                diagnostics, new ObjectMapper(), session);
    }
}
