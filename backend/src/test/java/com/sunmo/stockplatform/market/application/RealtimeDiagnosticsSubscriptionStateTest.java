/*
 * RealtimeDiagnostics(실시간 연결 진단 정보)의 구독 상태 표시를 확인해요.
 * 화면에 구독 상태가 잘못 보이면 운영자가 정상 구독을 끊긴 것으로 오해할 수 있어요.
 */
package com.sunmo.stockplatform.market.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 실시간 진단의 구독 상태 전이를 점검하는 테스트예요. */
class RealtimeDiagnosticsSubscriptionStateTest {
    /**
     * 연결 중 이미 구독 승인된 종목이 늦게 온 대기(queued) 기록 때문에 대기 상태로 바뀌지 않는지 확인해요.
     * 준비: 연결 → 구독 요청 → 승인 뒤 queued를 다시 호출하고, 이후 실제 연결 끊김도 만들어요.
     * 기대: 연결 중에는 SUBSCRIBED 유지, 끊긴 뒤에는 QUEUED로 초기화돼요.
     */
    @Test
    void aLateQueueDoesNotHideAnAcknowledgedSubscriptionWhileConnected() {
        RealtimeDiagnostics diagnostics = new RealtimeDiagnostics();
        diagnostics.connected();
        diagnostics.subscriptionRequested("000400");
        diagnostics.subscriptionAcknowledged("000400", true, "SUBSCRIBE SUCCESS");

        diagnostics.queued("000400");
        assertThat(diagnostics.snapshot().subscriptions().get("000400").state())
                .isEqualTo(RealtimeDiagnostics.SubscriptionState.SUBSCRIBED);

        diagnostics.disconnected();
        assertThat(diagnostics.snapshot().subscriptions().get("000400").state())
                .as("a real disconnect still resets").isEqualTo(RealtimeDiagnostics.SubscriptionState.QUEUED);
        diagnostics.queued("005930");
        assertThat(diagnostics.snapshot().subscriptions().get("005930").state())
                .isEqualTo(RealtimeDiagnostics.SubscriptionState.QUEUED);
    }
}
