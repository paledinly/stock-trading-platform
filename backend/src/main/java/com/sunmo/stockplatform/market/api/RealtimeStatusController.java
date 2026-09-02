package com.sunmo.stockplatform.market.api;

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

    public RealtimeStatusController(RealtimeDiagnostics diagnostics, RealtimeSubscriptionRegistry subscriptions,
                                    ObjectProvider<StringRedisTemplate> redis) {
        this.diagnostics = diagnostics;
        this.subscriptions = subscriptions;
        this.redis = redis.getIfAvailable();
    }

    @GetMapping("/status")
    public Status status() {
        return new Status(diagnostics.snapshot(), redisStatus(), subscriptions.all().size(),
                subscriptions.limit(), subscriptions.remaining());
    }

    private String redisStatus() {
        if (redis == null) return "UNAVAILABLE";
        try (var connection = redis.getRequiredConnectionFactory().getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping()) ? "UP" : "DOWN";
        }
        catch (RuntimeException error) { return "DOWN"; }
    }

    public record Status(RealtimeDiagnostics.Snapshot realtime, String redisStatus,
                         int subscriptionCount, int subscriptionLimit, int subscriptionRemaining) {}
}
