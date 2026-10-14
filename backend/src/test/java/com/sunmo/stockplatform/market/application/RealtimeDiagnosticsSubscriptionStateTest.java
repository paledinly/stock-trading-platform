package com.sunmo.stockplatform.market.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeDiagnosticsSubscriptionStateTest {
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
