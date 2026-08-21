package com.sunmo.stockplatform.kis.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.market.application.MarketDataService;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "market.realtime", name = "enabled", havingValue = "true")
public class KisRealtimeClient implements ApplicationRunner, WebSocket.Listener {
    private static final Logger log = LoggerFactory.getLogger(KisRealtimeClient.class);
    private static final long RECONNECT_DELAY_SECONDS = 5;
    private static final long SUBSCRIPTION_INTERVAL_MILLIS = 500;

    private final KisApprovalClient approval;
    private final KisRealtimeTickParser parser;
    private final MarketDataService market;
    private final RealtimeSubscriptionRegistry subscriptions;
    private final RealtimeMarketProperties properties;
    private final RealtimeDiagnostics diagnostics;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "kis-ws-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final Object subscriptionLock = new Object();
    private final StringBuilder fragments = new StringBuilder();
    private volatile WebSocket socket;
    private volatile String approvalKey;
    private long nextSubscriptionAt;

    public KisRealtimeClient(KisApprovalClient approval, KisRealtimeTickParser parser, MarketDataService market,
                             RealtimeSubscriptionRegistry subscriptions, RealtimeMarketProperties properties,
                             RealtimeDiagnostics diagnostics, ObjectMapper objectMapper) {
        this.approval = approval;
        this.parser = parser;
        this.market = market;
        this.subscriptions = subscriptions;
        this.properties = properties;
        this.diagnostics = diagnostics;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        subscriptions.onAdded(code -> {
            diagnostics.queued(code);
            subscribe(code);
        });
        subscriptions.all().forEach(diagnostics::queued);
        connect();
    }

    private void connect() {
        if (!connecting.compareAndSet(false, true)) return;
        try {
            approvalKey = approval.issue();
            HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(properties.websocketUrl(), this)
                    .whenComplete((webSocket, error) -> {
                        connecting.set(false);
                        if (error != null) {
                            scheduleReconnect(error);
                            return;
                        }
                        socket = webSocket;
                        synchronized (subscriptionLock) {
                            nextSubscriptionAt = 0;
                        }
                        log.info("KIS websocket connected");
                        subscriptions.all().stream().sorted(Comparator.naturalOrder()).forEach(this::subscribe);
                    });
        } catch (RuntimeException error) {
            connecting.set(false);
            scheduleReconnect(error);
        }
    }

    private void scheduleReconnect(Throwable error) {
        if (stopping.get()) return;
        diagnostics.disconnected();
        if (!reconnectScheduled.compareAndSet(false, true)) return;
        log.warn("KIS websocket disconnected; retrying in {} seconds: {}",
                RECONNECT_DELAY_SECONDS, rootMessage(error));
        scheduler.schedule(() -> {
            reconnectScheduled.set(false);
            connect();
        }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private void subscribe(String code) {
        WebSocket current = socket;
        if (current == null || current.isOutputClosed() || approvalKey == null) {
            diagnostics.queued(code);
            return;
        }
        long now = System.currentTimeMillis();
        long sendAt;
        synchronized (subscriptionLock) {
            sendAt = Math.max(now, nextSubscriptionAt);
            nextSubscriptionAt = sendAt + SUBSCRIPTION_INTERVAL_MILLIS;
        }
        scheduler.schedule(() -> sendSubscription(current, code), Math.max(0, sendAt - now), TimeUnit.MILLISECONDS);
    }

    private void sendSubscription(WebSocket expected, String code) {
        if (socket != expected || expected.isOutputClosed()) {
            diagnostics.queued(code);
            return;
        }
        String message = "{\"header\":{\"approval_key\":\"" + approvalKey
                + "\",\"custtype\":\"P\",\"tr_type\":\"1\",\"content-type\":\"utf-8\"},"
                + "\"body\":{\"input\":{\"tr_id\":\"H0STCNT0\",\"tr_key\":\"" + code + "\"}}}";
        diagnostics.subscriptionRequested(code);
        log.info("Requesting KIS H0STCNT0 subscription for {}", code);
        expected.sendText(message, true).whenComplete((ignored, error) -> {
            if (error != null) {
                diagnostics.subscriptionAcknowledged(code, false, "send failed: " + rootMessage(error));
                log.warn("Failed to send KIS subscription for {}: {}", code, rootMessage(error));
            }
        });
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        diagnostics.connected();
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        fragments.append(data);
        if (last) {
            String message = fragments.toString();
            fragments.setLength(0);
            handle(webSocket, message);
        }
        webSocket.request(1);
        return null;
    }

    private void handle(WebSocket webSocket, String message) {
        diagnostics.messageReceived();
        try {
            if (message.startsWith("{")) {
                handleControlMessage(webSocket, message);
                return;
            }
            if (!message.startsWith("0|H0STCNT0|")) return;
            String[] parts = message.split("\\|", 4);
            if (parts.length != 4) throw new IllegalArgumentException("Invalid H0STCNT0 frame");
            int count = Integer.parseInt(parts[2]);
            var ticks = parser.parseMany(parts[3], count);
            diagnostics.ticksReceived(ticks.size());
            ticks.forEach(market::onTick);
        } catch (RuntimeException error) {
            diagnostics.parseFailed();
            log.warn("Ignored invalid KIS realtime message: {}", rootMessage(error));
        }
    }

    private void handleControlMessage(WebSocket webSocket, String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String trId = root.path("header").path("tr_id").asText();
            if ("PINGPONG".equals(trId)) {
                diagnostics.pingReceived();
                webSocket.sendText(message, true);
                return;
            }
            if (!"H0STCNT0".equals(trId)) return;
            String code = root.path("header").path("tr_key").asText();
            String resultCode = root.path("body").path("rt_cd").asText();
            String resultMessage = root.path("body").path("msg1").asText();
            boolean success = "0".equals(resultCode);
            if (code.isBlank()) {
                log.warn("KIS H0STCNT0 response omitted stock code: code={}, message={}", resultCode, resultMessage);
                return;
            }
            diagnostics.subscriptionAcknowledged(code, success, resultMessage);
            if (success) log.info("KIS H0STCNT0 subscription accepted for {}: {}", code, resultMessage);
            else log.warn("KIS H0STCNT0 subscription rejected for {}: code={}, message={}",
                    code, resultCode, resultMessage);
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid KIS control message", error);
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int status, String reason) {
        if (socket == webSocket) socket = null;
        if (!stopping.get() && status != WebSocket.NORMAL_CLOSURE) {
            scheduleReconnect(new IllegalStateException("close " + status + ": " + reason));
        }
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (socket == webSocket) socket = null;
        scheduleReconnect(error);
    }

    @PreDestroy
    void shutdown() {
        stopping.set(true);
        WebSocket current = socket;
        socket = null;
        if (current != null && !current.isOutputClosed()) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "application shutdown");
        }
        scheduler.shutdownNow();
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}