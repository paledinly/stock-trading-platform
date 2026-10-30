/*
 * KIS 토큰의 실제 만료 시각을 읽고, "만료된 토큰" 응답 뒤 캐시를 비우는지 확인해요.
 * 2026-10-30 11:50~15:21 동안 만료된 토큰을 계속 써서 KIS 호출이 실패한 일을 막기 위한 테스트예요.
 */
package com.sunmo.stockplatform.kis.auth;

import com.sunmo.stockplatform.kis.config.KisProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 토큰 만료 처리 테스트예요. */
class KisTokenExpiryTest {
    private final KisProperties properties = new KisProperties(true, URI.create("https://example.test"), "key", "secret",
            Duration.ofMinutes(5), Duration.ofSeconds(3), Duration.ofSeconds(5),
            new KisProperties.Master(false, "0 0 0 * * *", URI.create("https://example.test/kospi"),
                    URI.create("https://example.test/kosdaq")));

    /** 시각을 앞으로 돌릴 수 있는 테스트용 시계예요. */
    static final class MovingClock extends Clock {
        Instant now;
        MovingClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** 기대: KST 만료 시각 문자열을 정확한 순간으로 읽고, 형식이 틀리면 null이에요. */
    @Test
    void parsesAbsoluteExpiryInSeoulTime() {
        assertThat(HttpKisAuthClient.parseExpiresAt("2026-10-30 11:50:21")).isEqualTo(Instant.parse("2026-10-30T02:50:21Z"));
        assertThat(HttpKisAuthClient.parseExpiresAt("")).isNull();
        assertThat(HttpKisAuthClient.parseExpiresAt("tomorrow")).isNull();
    }

    /**
     * 기대: 방금(1분 안) 받은 토큰은 비우지 않고, 1분이 지난 토큰은 비워서 다음 조회가 새 토큰을 받아요.
     */
    @Test
    void invalidatesOnlyTokensOlderThanAMinute() {
        var clock = new MovingClock(Instant.parse("2026-10-30T02:00:00Z"));
        var calls = new AtomicInteger();
        KisAuthClient auth = () -> new KisAccessToken("token-" + calls.incrementAndGet(), clock.now.plusSeconds(86_400));
        var manager = new KisTokenManager(auth, properties, clock);

        assertThat(manager.getAccessToken()).isEqualTo("token-1");
        assertThat(manager.invalidateExpired()).isFalse();
        clock.now = clock.now.plusSeconds(120);
        assertThat(manager.invalidateExpired()).isTrue();
        assertThat(manager.getAccessToken()).isEqualTo("token-2");
        assertThat(calls).hasValue(2);
    }
}
