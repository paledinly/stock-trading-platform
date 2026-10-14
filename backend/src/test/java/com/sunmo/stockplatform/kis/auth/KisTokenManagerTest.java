/*
 * KisTokenManager가 KIS(한국투자증권) 접근 토큰을 재사용하고, 만료가 가까우면 새로 받는지 확인해요.
 * KIS는 토큰 발급 횟수에 제한이 있어서 불필요한 재발급을 막는 것이 중요해요.
 */
package com.sunmo.stockplatform.kis.auth;

import com.sunmo.stockplatform.kis.config.KisProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** KIS 접근 토큰 캐시와 갱신 시점을 점검하는 테스트예요. */
class KisTokenManagerTest {
    // 고정 시계 Clock(항상 같은 시각을 돌려주는 시계)로 시간을 고정해요.
    private final Instant now = Instant.parse("2026-08-17T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final KisProperties properties = new KisProperties(true, URI.create("https://example.test"), "key",
            "secret",
            Duration.ofMinutes(5), Duration.ofSeconds(3), Duration.ofSeconds(5),
            new KisProperties.Master(false, "0 0 0 * * *", URI.create("https://example.test/kospi"),
                    URI.create("https://example.test/kosdaq")));

    /**
     * 갱신 여유 구간 전에는 같은 토큰을 재사용하는지 확인해요.
     * 준비: 1시간 뒤 만료되는 토큰을 주는 가짜 인증 클라이언트를 만들어요.
     * 기대: 두 번 조회해도 token-1이고 발급은 1번만 일어나요.
     */
    @Test
    void reusesTokenUntilRefreshWindow() {
        var calls = new AtomicInteger();
        KisAuthClient auth = () -> new KisAccessToken("token-" + calls.incrementAndGet(), now.plusSeconds(3600));
        var manager = new KisTokenManager(auth, properties, clock);

        assertThat(manager.getAccessToken()).isEqualTo("token-1");
        assertThat(manager.getAccessToken()).isEqualTo("token-1");
        assertThat(calls).hasValue(1);
    }

    /**
     * 만료가 갱신 여유 구간(설정상 5분으로 보여요) 안에 들어오면 새 토큰을 받는지 확인해요.
     * 준비: 첫 토큰은 60초 뒤 만료, 두 번째는 1시간 뒤 만료되게 해요.
     * 기대: 첫 조회는 token-1, 두 번째 조회는 token-2예요.
     */
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
