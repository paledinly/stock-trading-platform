/*
 * RealtimeDiagnostics(실시간 연결 진단 정보)가 연결 상태, 수신 건수, 구독 상태, 처리 시간 통계를 맞게 보여 주는지 확인해요.
 * 운영자가 실시간 수신이 멈췄는지 판단하는 근거라서 중요해요.
 */
package com.sunmo.stockplatform.market;

import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 실시간 진단 정보 집계를 점검하는 테스트예요. */
class RealtimeDiagnosticsTest {
    /**
     * 새 연결은 어제 받은 마지막 메시지 때문에 바로 끊긴 것(stale)으로 보이지 않는지 확인해요.
     * 준비: 연결 후 마지막 메시지 시각을 하루 전으로 강제로 바꿔요.
     * 기대: 30초 뒤는 정상, 61초 뒤(기준 60초 초과)는 stale이에요.
     */
    @Test
    void newSessionDoesNotBecomeStaleBecauseLastMessageWasYesterday() {
        var diagnostics = new RealtimeDiagnostics();
        diagnostics.connected();
        org.springframework.test.util.ReflectionTestUtils.setField(diagnostics, "lastMessageAt", Instant.now().minus(Duration.ofDays(1)));
        assertThat(diagnostics.isConnectionStale(Instant.now().plusSeconds(30), Duration.ofSeconds(60))).isFalse();
        assertThat(diagnostics.isConnectionStale(Instant.now().plusSeconds(61), Duration.ofSeconds(60))).isTrue();
    }

    /**
     * 연결·구독·틱 관련 수치가 스냅샷에 드러나는지 확인해요.
     * 준비: 연결, 대기, 구독 요청·승인, 메시지 1, 틱 2, 처리 실패 1, 특성 스냅샷 1을 기록해요.
     * 기대: 각 수치가 그대로 나오고 005930은 SUBSCRIBED 상태예요.
     */
    @Test
    void exposesConnectionSubscriptionAndTickState() {
        RealtimeDiagnostics diagnostics = new RealtimeDiagnostics();

        // 실행
        diagnostics.connected();
        diagnostics.queued("005930");
        diagnostics.subscriptionRequested("005930");
        diagnostics.subscriptionAcknowledged("005930", true, "SUBSCRIBE SUCCESS");
        diagnostics.messageReceived();
        diagnostics.ticksReceived(2);
        diagnostics.processingFailed("005930", "test failure");
        diagnostics.featureSnapshot(1);

        // 확인
        var snapshot = diagnostics.snapshot();
        assertThat(snapshot.connected()).isTrue();
        assertThat(snapshot.receivedFrames()).isEqualTo(1);
        assertThat(snapshot.receivedTicks()).isEqualTo(2);
        assertThat(snapshot.parseErrors()).isZero();
        assertThat(snapshot.processingErrors()).isEqualTo(1);
        assertThat(snapshot.featureSnapshots()).isEqualTo(1);
        assertThat(snapshot.featureTrackedStocks()).isEqualTo(1);
        assertThat(snapshot.subscriptionSuccesses()).isEqualTo(1);
        assertThat(snapshot.subscriptions().get("005930").state())
                .isEqualTo(RealtimeDiagnostics.SubscriptionState.SUBSCRIBED);
    }

    /**
     * 연결된 채 메시지가 끊긴 소켓을 감지하는지 확인해요.
     * 준비: 연결 직후 61초 뒤 시각으로 검사하고, 끊은 뒤 다시 검사해요.
     * 기대: 연결 중엔 stale(true), 끊긴 뒤엔 stale 판정을 하지 않아요(false).
     */
    @Test
    void detectsAConnectedSocketThatStoppedReceivingMessages() {
        RealtimeDiagnostics diagnostics = new RealtimeDiagnostics();
        diagnostics.connected();

        assertThat(diagnostics.isConnectionStale(Instant.now().plusSeconds(61), Duration.ofSeconds(60))).isTrue();

        diagnostics.disconnected();
        assertThat(diagnostics.isConnectionStale(Instant.now().plusSeconds(61), Duration.ofSeconds(60))).isFalse();
    }

    /**
     * 원천 시각이 수신 시각보다 늦은(시계 차이) 음수 지연도 최솟값으로 보여 주는지 확인해요.
     * 준비: -800ms와 +400ms 지연을 기록해요.
     * 기대: 최소 -800, 음수 1건, 평균 200, 최대 400이에요.
     */
    @Test
    void sourceAfterReceiptStaysVisibleAsNegativeMinimumWithoutSkewingAverages() {
        RealtimeDiagnostics diagnostics = new RealtimeDiagnostics();
        diagnostics.timing("sourceToCallback", -800_000_000L);
        diagnostics.timing("sourceToCallback", 400_000_000L);

        RealtimeDiagnostics.Timing timing = diagnostics.snapshot().timings().get("sourceToCallback");

        assertThat(timing.minMs()).isEqualTo(-800);
        assertThat(timing.negativeCount()).isEqualTo(1);
        assertThat(timing.averageMs()).isEqualTo(200);
        assertThat(timing.maxMs()).isEqualTo(400);
    }
}
