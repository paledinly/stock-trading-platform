/*
 * KIS 실시간 WebSocket(서버와 연결을 계속 열어 두고 데이터를 실시간으로 주고받는 통신 방식)에 접속해 종목별 체결(H0STCNT0)을 구독하고 받아요.
 * 받은 체결은 해석기로 MarketTick으로 바꾼 뒤 처리 대기열(RealtimeTickProcessor)에 넘기고, 끊기거나 조용해지면 다시 연결해요.
 * market.realtime.enabled=true일 때만 켜지며, 구독 목록은 RealtimeSubscriptionRegistry(관심종목·정밀 구독 배정)가 바꿔요.
 */
package com.sunmo.stockplatform.kis.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.market.application.MarketDataService;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import com.sunmo.stockplatform.market.application.RealtimeSessionPolicy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KIS 실시간 체결 방송을 듣는 수신기 역할의 클래스예요.
 * 라디오에 주파수(종목코드)를 맞춰 두고 방송이 들리면 받아 적고, 잡음만 계속되면 다시 주파수를 맞추는 무전병과 같아요.
 * 실주문은 보내지 않고 시세 수신만 해요.
 */
@Component
@ConditionalOnProperty(prefix = "market.realtime", name = "enabled", havingValue = "true")
public class KisRealtimeClient implements ApplicationRunner, WebSocket.Listener {
    private static final Logger log = LoggerFactory.getLogger(KisRealtimeClient.class);
    // 재연결 대기 시간(5초)과 구독 요청 사이 최소 간격(0.5초)이에요. KIS에 구독 요청을 한꺼번에 몰아 보내지 않으려는 것으로 보여요.
    private static final long RECONNECT_DELAY_SECONDS = 5;
    private static final long SUBSCRIPTION_INTERVAL_MILLIS = 500;

    private final KisApprovalClient approval;
    private final KisRealtimeTickParser parser;
    private final RealtimeTickProcessor processor;
    // 지금 조립 중인 메시지의 첫 조각을 받은 시각이에요. 메시지 전체의 서버 수신시각으로 써요.
    private Instant frameReceivedAt;
    private final RealtimeSubscriptionRegistry subscriptions;
    private final RealtimeMarketProperties properties;
    private final RealtimeDiagnostics diagnostics;
    private final ObjectMapper objectMapper;
    private final RealtimeSessionPolicy session;
    // 재연결과 구독 요청 예약에 쓰는 스케줄러(정해진 시간에 작업을 실행해 주는 장치)예요. 스레드 하나로 순서대로 실행해요.
    // 연결 중 QUEUED로 이 시간보다 오래 남은 구독은 전송이 빠진 것으로 보고 다시 보내요.
    static final Duration STUCK_QUEUED_AFTER = Duration.ofSeconds(10);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "kis-ws-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    // 연결 시도 중, 재연결 예약됨, 종료 중 상태를 여러 스레드에서 안전하게 확인하기 위한 깃발들이에요.
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    // 응답을 아직 못 받은 구독(true)·해지(false) 요청이에요. KIS 응답이 구독 결과인지 해지 결과인지 구분할 때 써요.
    private final java.util.Map<String, Boolean> pendingSubscriptions = new ConcurrentHashMap<>();
    private final Object subscriptionLock = new Object();
    // WebSocket 메시지가 여러 조각으로 나뉘어 올 때 이어 붙이는 버퍼예요.
    private final StringBuilder fragments = new StringBuilder();
    // 구독 성공·해지·연결 끊김을 실시간 수신 장부(장중 재시작·재연결 빈 구간)에 알릴 때 써요.
    private final MarketDataService market;
    private volatile WebSocket socket;
    private volatile String approvalKey;
    private long nextSubscriptionAt;

    /**
     * 처리 대기열 크기를 기본값(20000)으로 쓰는 생성자예요. 주로 테스트에서 쓰는 것으로 보여요.
     * 받는 것: 접속키 발급기, 체결 해석기, 시세 처리 서비스, 구독 목록, 실시간 설정, 진단 기록기, JSON 변환기, 장 운영시간 정책.
     * 돌려주는 것: 실시간 수신기 객체.
     */
    public KisRealtimeClient(KisApprovalClient approval, KisRealtimeTickParser parser, MarketDataService market,
            RealtimeSubscriptionRegistry subscriptions, RealtimeMarketProperties properties,
            RealtimeDiagnostics diagnostics, ObjectMapper objectMapper, RealtimeSessionPolicy session) {
        this(approval, parser, market, subscriptions, properties, diagnostics, objectMapper, session, 20000);
    }

    /**
     * 의존성 주입(필요한 부품을 스프링이 대신 만들어 넣어 주는 방식)에 쓰이는 생성자예요.
     * 받는 것: 위 생성자와 같은 부품들 + 설정의 처리 대기열 크기(market.realtime.processing-queue-capacity).
     * 돌려주는 것: 체결 처리기(RealtimeTickProcessor)까지 만들어 둔 실시간 수신기 객체.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public KisRealtimeClient(KisApprovalClient approval, KisRealtimeTickParser parser, MarketDataService market,
            RealtimeSubscriptionRegistry subscriptions, RealtimeMarketProperties properties,
            RealtimeDiagnostics diagnostics, ObjectMapper objectMapper, RealtimeSessionPolicy session,
            @org.springframework.beans.factory.annotation.Value("${market.realtime.processing-queue-capacity:20000}") int queueCapacity) {
        this.approval = approval;
        this.parser = parser;
        this.market = market;
        this.subscriptions = subscriptions;
        this.properties = properties;
        this.diagnostics = diagnostics;
        this.objectMapper = objectMapper;
        this.session = session;
        this.processor = new RealtimeTickProcessor(market, diagnostics, queueCapacity);
    }

    /**
     * 객체가 만들어지자마자 구독 목록 변경을 KIS 구독/해지 요청으로 잇는 연결을 걸어요.
     * 예전에는 이 연결을 run(기동 작업)에서 걸었는데, 앞선 기동 작업(성과 복구)이 오래 걸리는 사이 30초 연결 점검이 먼저
     * 접속하고 시장 스캔이 정밀 종목을 추가하면, 그 종목들은 요청 없이 목록에만 남았어요(2026-10-15 13:51 재시작, 31종목).
     * 받는 것: 없음. 돌려주는 것: 없음.
     */
    @PostConstruct
    void listenToSubscriptionChanges() {
        subscriptions.onChanged((code, subscribe) -> {
            if (subscribe) {
                diagnostics.queued(code);
                send(code, true);
            } else {
                send(code, false);
            }
        });
    }

    /**
     * 서버가 다 켜진 뒤 한 번 실행돼요. 아직 연결 전인 구독을 "대기 중"으로 표시하고 첫 접속을 시작해요.
     * 이미 연결돼 있으면(30초 점검이 먼저 접속한 경우) connect는 아무것도 하지 않고, 이미 수신 중인 종목은 대기로 바꾸지 않아요.
     * 받는 것: 실행 인자(사용 안 함). 돌려주는 것: 없음.
     */
    @Override
    public void run(ApplicationArguments args) {
        subscriptions.all().forEach(diagnostics::queued);
        connect();
    }

    /**
     * KIS WebSocket에 접속을 시작해요. 이미 연결됐거나, 연결 중이거나, 장 운영시간 밖이면 아무것도 하지 않아요.
     * 받는 것: 없음. 돌려주는 것: 없음. 실패하면 재연결을 예약해요.
     */
    private void connect() {
        if (stopping.get() || !session.connectionAllowed() || socket != null || !connecting.compareAndSet(false, true))
            return;
        try {
            // 준비: 연결할 때마다 새 접속키를 받아요. 연결 자체는 비동기(결과를 기다리지 않고 나중에 완료 알림을 받는 방식)로 진행돼요.
            approvalKey = approval.issue();
            HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(properties.websocketUrl(), this)
                    .whenComplete((webSocket, error) -> {
                        connecting.set(false);
                        if (error != null) {
                            scheduleReconnect(error);
                            return;
                        }
                        // onOpen에서 등록한 소켓과 다르거나 그사이 종료·장 마감이 되었으면 이 연결은 버려요.
                        if (stopping.get() || !session.connectionAllowed() || socket != webSocket) {
                            webSocket.abort();
                            return;
                        }
                        // 결과: 새 연결이므로 구독 간격 예약을 초기화하고 전체 구독을 종목코드 순으로 다시 요청해요.
                        synchronized (subscriptionLock) {
                            nextSubscriptionAt = 0;
                        }
                        log.info("KIS websocket connected");
                        subscriptions.all().stream().sorted(Comparator.naturalOrder())
                                .forEach(code -> send(code, true));
                    });
        } catch (RuntimeException error) {
            connecting.set(false);
            scheduleReconnect(error);
        }
    }

    /**
     * 연결이 끊겼을 때 기록을 남기고 5초 뒤 재연결을 한 번만 예약해요.
     * 받는 것: 끊긴 원인(error). 돌려주는 것: 없음.
     * 처리기에 끊김을 알려, 이후 체결이 "데이터 손실 가능"으로 표시되게 해요.
     */
    private void scheduleReconnect(Throwable error) {
        processor.disconnected();
        market.realtimeDisconnected(Instant.now());
        diagnostics.disconnected();
        diagnostics.error("WebSocket disconnect: " + error.getClass().getSimpleName());
        if (stopping.get() || !session.connectionAllowed())
            return;
        if (!reconnectScheduled.compareAndSet(false, true))
            return;
        log.warn("KIS websocket disconnected; retrying in {} seconds: {}",
                RECONNECT_DELAY_SECONDS, rootMessage(error));
        scheduler.schedule(() -> {
            reconnectScheduled.set(false);
            connect();
        }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 일정 간격(기본 30초)마다 연결 상태를 점검해요.
     * 장 운영시간 밖이면 연결을 닫아 쉬고, 연결이 없으면 다시 접속하고, 너무 오래 메시지가 없으면 끊고 재연결해요.
     * 받는 것: 없음. 돌려주는 것: 없음.
     */
    @Scheduled(fixedDelayString = "${market.realtime.health-check-interval:30s}")
    public void reconnectIfStale() {
        Instant now = Instant.now();
        if (stopping.get()) return;
        // 장 운영시간 밖: 소켓과 접속키를 비우고 연결을 닫아요.
        if (!session.connectionAllowed()) {
            WebSocket current = socket;
            socket = null;
            approvalKey = null;
            pendingSubscriptions.clear();
            market.realtimePaused();
            if (current != null) {
                current.abort();
                diagnostics.disconnected();
                log.info("KIS websocket paused outside trading session");
            }
            return;
        }
        // 연결이 없고 재연결 예약도 없으면 바로 접속을 시도해요.
        if (socket == null) {
            if (!reconnectScheduled.get()) connect();
            return;
        }
        // 연결은 있지만 staleTimeout 동안 메시지가 없으면 죽은 연결로 보고 끊은 뒤 재연결해요.
        if (!diagnostics.isConnectionStale(now, properties.staleTimeout())) {
            resendStuckSubscriptions(now);
            return;
        }
        WebSocket current = socket;
        socket = null;
        if (current != null)
            current.abort();
        diagnostics.error("WebSocket stale timeout: " + properties.staleTimeout());
        scheduleReconnect(new IllegalStateException("no KIS realtime message for " + properties.staleTimeout()));
    }

    /**
     * 안전장치: 연결돼 있는데 구독 목록에 있는 종목이 QUEUED(전송 안 됨)로 10초 넘게 남아 있으면 다시 보내요.
     * 왜 전송이 빠졌는지와 상관없이, 연결 점검(30초)마다 멈춘 구독을 복구해요. 받는 것: 현재 시각. 돌려주는 것: 없음.
     */
    private void resendStuckSubscriptions(Instant now) {
        java.util.Set<String> wanted = subscriptions.all();
        java.util.List<String> stuck = diagnostics.stuckQueued(now, STUCK_QUEUED_AFTER).stream()
                .filter(wanted::contains).sorted().toList();
        if (stuck.isEmpty())
            return;
        log.warn("Resending {} realtime subscriptions stuck in QUEUED while connected", stuck.size());
        stuck.forEach(code -> send(code, true));
    }

    /**
     * 종목 하나의 구독 또는 해지 요청을 예약해요. 요청끼리 최소 0.5초 간격이 되도록 보낼 시각을 정해요.
     * 받는 것: 종목코드, 구독 여부(true 구독, false 해지). 돌려주는 것: 없음.
     * 연결이 없으면 보내지 않아요. 구독이면 "대기 중"으로 표시하고(재연결 때 전체 목록을 다시 구독해요),
     * 해지면 재연결 때 다시 구독되지 않으므로 진단의 구독 상태 항목을 지워요.
     */
    private void send(String code, boolean subscribe) {
        WebSocket current = socket;
        if (current == null || current.isOutputClosed() || approvalKey == null) {
            markUnsent(code, subscribe);
            return;
        }
        long now = System.currentTimeMillis();
        long sendAt;
        synchronized (subscriptionLock) {
            sendAt = Math.max(now, nextSubscriptionAt);
            nextSubscriptionAt = sendAt + SUBSCRIPTION_INTERVAL_MILLIS;
        }
        scheduler.schedule(() -> sendSubscription(current, code, subscribe), Math.max(0, sendAt - now),
                TimeUnit.MILLISECONDS);
    }

    /**
     * 예약된 시각에 실제 구독/해지 메시지를 KIS로 보내요.
     * 받는 것: 예약할 때의 소켓(expected), 종목코드, 구독 여부. 돌려주는 것: 없음.
     * 그사이 소켓이 바뀌었거나 장 운영시간이 끝났으면 보내지 않아요.
     */
    private void sendSubscription(WebSocket expected, String code, boolean subscribe) {
        if (stopping.get() || !session.connectionAllowed() || socket != expected || expected.isOutputClosed()) {
            markUnsent(code, subscribe);
            return;
        }
        // 메시지에는 접속키가 들어 있으니 로그에 그대로 남기지 않아요. tr_type 1은 구독, 2는 해지예요.
        String message = "{\"header\":{\"approval_key\":\"" + approvalKey
                + "\",\"custtype\":\"P\",\"tr_type\":\"" + (subscribe ? "1" : "2") + "\",\"content-type\":\"utf-8\"},"
                + "\"body\":{\"input\":{\"tr_id\":\"H0STCNT0\",\"tr_key\":\"" + code + "\"}}}";
        pendingSubscriptions.put(code, subscribe);
        if (subscribe)
            diagnostics.subscriptionRequested(code);
        log.info("Requesting KIS H0STCNT0 {} for {}", subscribe ? "subscription" : "unsubscription", code);
        expected.sendText(message, true).whenComplete((ignored, error) -> {
            if (error != null) {
                if (subscribe)
                    diagnostics.subscriptionAcknowledged(code, false, "send failed: " + rootMessage(error));
                log.warn("Failed to send KIS subscription for {}: {}", code, rootMessage(error));
            }
        });
    }

    /**
     * 연결이 없어 보내지 못한 요청의 진단 상태를 정리해요.
     * 받는 것: 종목코드, 구독 여부. 구독이면 QUEUED로 표시하고, 해지면 구독 상태 항목을 지워요
     * (해지된 종목은 구독 목록에 없어서 재연결 때 다시 구독되지 않으니 "대기 중"으로 남기면 안 돼요).
     */
    private void markUnsent(String code, boolean subscribe) {
        if (subscribe)
            diagnostics.queued(code);
        else
            diagnostics.subscriptionDropped(code);
    }

    /**
     * WebSocket 연결이 열렸을 때 불려요. 현재 소켓으로 등록하고 첫 메시지를 요청해요.
     * 받는 것: 열린 WebSocket. 돌려주는 것: 없음.
     */
    @Override
    public synchronized void onOpen(WebSocket webSocket) {
        if (stopping.get() || !session.connectionAllowed()) { webSocket.abort(); return; }
        socket = webSocket;
        fragments.setLength(0);
        diagnostics.connected();
        webSocket.request(1);
    }

    /**
     * 문자 메시지 조각을 받을 때마다 불려요. 조각을 모아 마지막 조각이 오면 한 메시지로 처리해요.
     * 받는 것: WebSocket, 받은 글자 조각(data), 마지막 조각인지(last). 돌려주는 것: null(바로 다음 메시지를 받을 준비가 됐다는 뜻).
     */
    @Override
    public synchronized CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        Instant callbackAt = Instant.now();
        if (stopping.get() || !session.connectionAllowed() || socket != webSocket) {
            webSocket.abort();
            return null;
        }
        // 첫 조각을 받은 시각을 메시지 전체의 서버 수신시각으로 기억해요.
        if (fragments.isEmpty()) frameReceivedAt = callbackAt;
        fragments.append(data);
        if (last) {
            String message = fragments.toString();
            fragments.setLength(0);
            handle(webSocket, message, frameReceivedAt);
        }
        // 다음 메시지를 한 개 더 달라고 요청해요(흐름 제어: 처리할 수 있는 만큼만 받기).
        webSocket.request(1);
        return null;
    }

    /**
     * 수신시각을 지금으로 정해 메시지를 처리해요. 받는 것: WebSocket, 메시지. 돌려주는 것: 없음.
     * 현재 이 클래스 안에서는 쓰이지 않는 것으로 보여요.
     */
    private void handle(WebSocket webSocket, String message) {
        handle(webSocket, message, Instant.now());
    }

    /**
     * 완성된 메시지 하나를 처리해요.
     * 받는 것: WebSocket, 메시지 전문, 서버 수신시각. 돌려주는 것: 없음.
     * JSON이면 제어 메시지(구독 응답·PINGPONG)로, "0|H0STCNT0|건수|본문" 형식이면 체결로 처리해요. 잘못된 메시지는 기록만 하고 무시해요.
     */
    private void handle(WebSocket webSocket, String message, Instant receivedAt) {
        diagnostics.messageReceived();
        try {
            if (message.startsWith("{")) {
                handleControlMessage(webSocket, message);
                return;
            }
            if (!message.startsWith("0|H0STCNT0|"))
                return;
            String[] parts = message.split("\\|", 4);
            if (parts.length != 4)
                throw new IllegalArgumentException("Invalid H0STCNT0 frame");
            // 처리: 머리에 적힌 체결 건수만큼 본문을 해석해요.
            int count = Integer.parseInt(parts[2]);
            var ticks = parser.parseMany(parts[3], count);
            diagnostics.ticksReceived(ticks.size());
            // 장 운영시간 밖의 체결은 버리고, 종가 단일가 체결은 따로 표시한 뒤 처리 대기열에 넣어요.
            for (var tick : ticks) {
                if (!session.accepts(tick)) { diagnostics.outsideSessionTick(tick.occurredAt()); continue; }
                if (session.closingAuctionPrint(tick)) diagnostics.closingAuctionTick(tick.occurredAt());
                processor.submit(tick, receivedAt);
            }
        } catch (RuntimeException error) {
            diagnostics.parseFailed(rootMessage(error));
            log.warn("Ignored invalid KIS realtime message: {}", rootMessage(error));
        }
    }

    /**
     * JSON 제어 메시지를 처리해요.
     * 받는 것: WebSocket, JSON 메시지. 돌려주는 것: 없음.
     * PINGPONG(연결 확인 신호)은 그대로 돌려보내고, H0STCNT0 구독 응답은 성공 여부를 진단과 구독 목록에 알려요.
     */
    private void handleControlMessage(WebSocket webSocket, String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String trId = root.path("header").path("tr_id").asText();
            if ("PINGPONG".equals(trId)) {
                diagnostics.pingReceived();
                webSocket.sendText(message, true);
                return;
            }
            if (!"H0STCNT0".equals(trId))
                return;
            String code = root.path("header").path("tr_key").asText();
            String resultCode = root.path("body").path("rt_cd").asText();
            String resultMessage = root.path("body").path("msg1").asText();
            if (code.isBlank()) {
                log.warn("KIS H0STCNT0 response omitted stock code: code={}, message={}", resultCode, resultMessage);
                return;
            }
            // 기다리던 요청이 해지(false)였을 때만 해지 응답으로 보고, 나머지는 구독 응답으로 처리해요.
            boolean subscribing = pendingSubscriptions.remove(code) != Boolean.FALSE;
            // 구독 요청에 "이미 구독 중"이 오면 KIS 쪽에서는 이미 체결을 보내 주고 있는 것이라 성공으로 봐요.
            // 2026-10-23 09:00 011330: 해지와 재구독이 엇갈려 이 응답이 왔는데, 실패로 처리해 정밀 종목에서 빠지고 해지까지 됐어요.
            boolean alreadySubscribed = subscribing && resultMessage.toUpperCase(java.util.Locale.ROOT).contains("ALREADY IN SUBSCRIBE");
            boolean success = "0".equals(resultCode) || alreadySubscribed;
            if (subscribing)
                diagnostics.subscriptionAcknowledged(code, success, resultMessage);
            else
                diagnostics.subscriptionRemoved(code, success, resultMessage);
            subscriptions.acknowledge(code, subscribing, success, resultMessage);
            if (success && subscribing)
                market.realtimeSubscribed(code, Instant.now());
            else if (success)
                market.realtimeUnsubscribed(code);
            if (alreadySubscribed)
                log.info("KIS H0STCNT0 subscription for {} was already active at KIS; treated as accepted", code);
            else if (success)
                log.info("KIS H0STCNT0 subscription accepted for {}: {}", code, resultMessage);
            else
                log.warn("KIS H0STCNT0 subscription rejected for {}: code={}, message={}",
                        code, resultCode, resultMessage);
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid KIS control message", error);
        }
    }

    /**
     * 서버가 연결을 닫았을 때 불려요. 현재 소켓이면 비우고 재연결을 예약해요.
     * 받는 것: WebSocket, 종료 코드, 사유. 돌려주는 것: null.
     */
    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int status, String reason) {
        if (socket != webSocket) return null;
        diagnostics.error("WebSocket close status: " + status);
        socket = null;
        if (!stopping.get()) {
            scheduleReconnect(new IllegalStateException("close " + status + ": " + reason));
        }
        return null;
    }

    /**
     * 연결 중 오류가 났을 때 불려요. 현재 소켓이면 비우고 재연결을 예약해요.
     * 받는 것: WebSocket, 오류. 돌려주는 것: 없음.
     */
    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (socket != webSocket) return;
        socket = null;
        scheduleReconnect(error);
    }

    /**
     * 서버가 꺼질 때 불려요. 종료 표시를 하고, 정상 종료 신호를 보낸 뒤 예약 작업과 체결 처리기를 멈춰요.
     * 받는 것: 없음. 돌려주는 것: 없음.
     */
    @PreDestroy
    void shutdown() {
        stopping.set(true);
        WebSocket current = socket;
        socket = null;
        if (current != null && !current.isOutputClosed()) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "application shutdown");
        }
        scheduler.shutdownNow();
        processor.close();
    }

    /**
     * 예외의 가장 안쪽 원인 메시지를 꺼내요. 받는 것: 예외. 돌려주는 것: 원인 메시지, 없으면 예외 클래스 이름.
     */
    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null)
            current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
