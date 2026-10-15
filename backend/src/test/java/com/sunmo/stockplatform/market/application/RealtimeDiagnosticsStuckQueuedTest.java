/*
 * RealtimeDiagnostics.stuckQueued 테스트예요. 연결 중 QUEUED로 오래 남은 구독(전송이 빠진 구독)만 골라내는지 확인해요.
 * 2026-10-15 13:51 장중 재시작 직후 정밀 31종목이 QUEUED로 남아 체결을 받지 못한 일을 막는 안전장치의 기준이에요.
 */
package com.sunmo.stockplatform.market.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeDiagnosticsStuckQueuedTest {
    @Test
    void returnsOnlyCodesQueuedLongerThanTheLimitWhileConnected() throws Exception {
        var diagnostics = new RealtimeDiagnostics();
        diagnostics.queued("000660");
        assertThat(diagnostics.stuckQueued(Instant.now().plusSeconds(30), Duration.ofSeconds(10)))
                .as("not connected: nothing to resend").isEmpty();

        diagnostics.connected();
        diagnostics.queued("005930");
        diagnostics.subscriptionRequested("035720");
        diagnostics.subscriptionAcknowledged("068270", true, "SUBSCRIBE SUCCESS");

        Instant later = Instant.now().plusSeconds(30);
        assertThat(diagnostics.stuckQueued(later, Duration.ofSeconds(10))).containsExactly("000660", "005930");
        assertThat(diagnostics.stuckQueued(Instant.now(), Duration.ofSeconds(10))).as("too recent").isEmpty();
    }
}
