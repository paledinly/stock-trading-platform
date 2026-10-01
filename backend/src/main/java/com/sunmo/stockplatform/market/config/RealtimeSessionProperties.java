package com.sunmo.stockplatform.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("market.realtime.session")
public record RealtimeSessionProperties(@DefaultValue("1m") Duration connectBeforeOpen,
        @DefaultValue("1m") Duration disconnectAfterClose) {
    public RealtimeSessionProperties {
        if (connectBeforeOpen.isNegative() || disconnectAfterClose.isNegative()
                || connectBeforeOpen.compareTo(Duration.ofHours(1)) > 0
                || disconnectAfterClose.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("Realtime session margins must be between zero and one hour");
    }
}
