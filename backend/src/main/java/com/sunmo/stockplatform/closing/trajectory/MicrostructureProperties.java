package com.sunmo.stockplatform.closing.trajectory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties("closing.trajectory.microstructure")
public record MicrostructureProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean orderbookEnabled,
        @DefaultValue("5s") Duration pollInterval, @DefaultValue("2") int maxSymbols,
        List<String> symbols, @DefaultValue("100000000") BigDecimal largeTradeThreshold,
        @DefaultValue("15s") Duration maxObservationGap, @DefaultValue("2s") Duration watermark) {
    public MicrostructureProperties {
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
        if (pollInterval.compareTo(Duration.ofSeconds(5)) < 0 || maxSymbols < 1 || maxSymbols > 5
                || symbols.size() > maxSymbols || symbols.stream().anyMatch(s -> !s.matches("[0-9]{6}"))
                || largeTradeThreshold.signum() <= 0 || maxObservationGap.compareTo(Duration.ofSeconds(5)) < 0
                || maxObservationGap.compareTo(Duration.ofSeconds(30)) > 0
                || watermark.isNegative() || watermark.compareTo(Duration.ofSeconds(10)) > 0)
            throw new IllegalArgumentException("Invalid microstructure observation policy");
    }
}
