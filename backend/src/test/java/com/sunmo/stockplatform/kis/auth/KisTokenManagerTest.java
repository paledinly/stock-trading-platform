package com.sunmo.stockplatform.kis.auth;

import com.sunmo.stockplatform.kis.config.KisProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class KisTokenManagerTest {
    private final Instant now = Instant.parse("2026-08-17T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final KisProperties properties = new KisProperties(true, URI.create("https://example.test"), "key", "secret",
            Duration.ofMinutes(5), Duration.ofSeconds(3), Duration.ofSeconds(5),
            new KisProperties.Master(false, "0 0 0 * * *", URI.create("https://example.test/kospi"), URI.create("https://example.test/kosdaq")));

    @Test
    void reusesTokenUntilRefreshWindow() {
        var calls = new AtomicInteger();
        KisAuthClient auth = () -> new KisAccessToken("token-" + calls.incrementAndGet(), now.plusSeconds(3600));
        var manager = new KisTokenManager(auth, properties, clock);

        assertThat(manager.getAccessToken()).isEqualTo("token-1");
        assertThat(manager.getAccessToken()).isEqualTo("token-1");
        assertThat(calls).hasValue(1);
    }

    @Test
    void refreshesTokenInsideSkewWindow() {
        var calls = new AtomicInteger();
        KisAuthClient auth = () -> new KisAccessToken("token-" + calls.incrementAndGet(),
                calls.get() == 1 ? now.plusSeconds(60) : now.plusSeconds(3600));
        var manager = new KisTokenManager(auth, properties, clock);

        assertThat(manager.getAccessToken()).isEqualTo("token-1");
        assertThat(manager.getAccessToken()).isEqualTo("token-2");
    }
}

