package com.sunmo.stockplatform.market;

import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeDiagnosticsTest {
    @Test
    void exposesConnectionSubscriptionAndTickState() {
        RealtimeDiagnostics diagnostics = new RealtimeDiagnostics();

        diagnostics.connected();
        diagnostics.queued("005930");
        diagnostics.subscriptionRequested("005930");
        diagnostics.subscriptionAcknowledged("005930", true, "SUBSCRIBE SUCCESS");
        diagnostics.messageReceived();
        diagnostics.ticksReceived(2);

        var snapshot = diagnostics.snapshot();
        assertThat(snapshot.connected()).isTrue();
        assertThat(snapshot.receivedFrames()).isEqualTo(1);
        assertThat(snapshot.receivedTicks()).isEqualTo(2);
        assertThat(snapshot.subscriptionSuccesses()).isEqualTo(1);
        assertThat(snapshot.subscriptions().get("005930").state())
                .isEqualTo(RealtimeDiagnostics.SubscriptionState.SUBSCRIBED);
    }
}
