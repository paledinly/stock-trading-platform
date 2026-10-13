package com.sunmo.stockplatform.market.api;

import com.sunmo.stockplatform.market.application.MarketEventGateway;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market/realtime")
public class RealtimeStatusController {
    private final RealtimeDiagnostics diagnostics;
    private final RealtimeSubscriptionRegistry subscriptions;
    private final StringRedisTemplate redis;
    private final MarketEventGateway stream;

    public RealtimeStatusController(RealtimeDiagnostics diagnostics, RealtimeSubscriptionRegistry subscriptions,
            ObjectProvider<StringRedisTemplate> redis, MarketEventGateway stream) {
        this.diagnostics = diagnostics;
        this.subscriptions = subscriptions;
        this.stream = stream;
        this.redis = redis.getIfAvailable();
    }

    @GetMapping("/status")
    public Status status() {
        return new Status(diagnostics.snapshot(), redisStatus(), subscriptions.all().size(),
                subscriptions.limit(), subscriptions.remaining(), stream.clientCount(), stream.slowClientDisconnects());
    }

    private String redisStatus() {
        if (redis == null)
            return "UNAVAILABLE";
        try (var connection = redis.getRequiredConnectionFactory().getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping()) ? "UP" : "DOWN";
        } catch (RuntimeException error) {
            return "DOWN";
        }
    }

    public record Status(RealtimeDiagnostics.Snapshot realtime, String redisStatus,
            int subscriptionCount, int subscriptionLimit, int subscriptionRemaining,
            int sseClients, long sseSlowClientDisconnects) {
    }
}
