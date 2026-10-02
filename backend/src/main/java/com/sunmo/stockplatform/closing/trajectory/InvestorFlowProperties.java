package com.sunmo.stockplatform.closing.trajectory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("closing.trajectory.flow")
public record InvestorFlowProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("20") int maxSymbols, @DefaultValue("5m") Duration interval) {
    public InvestorFlowProperties {
        if (maxSymbols < 1 || maxSymbols > 40 || interval.compareTo(Duration.ofMinutes(1)) < 0)
            throw new IllegalArgumentException("Invalid investor flow budget");
    }
}
