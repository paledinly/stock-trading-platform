package com.sunmo.stockplatform.closing.trajectory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class MicrostructureModel {
    private MicrostructureModel() {}
    public record Level(BigDecimal price, BigDecimal quantity) {
        public Level {
            if (price == null || quantity == null || price.signum() < 0 || quantity.signum() < 0
                    || price.signum() == 0 && quantity.signum() > 0) throw new IllegalArgumentException("Invalid depth level");
        }
    }
    public record Book(String symbol, Instant sourceAt, Instant receivedAt, List<Level> bids, List<Level> asks) {
        public Book {
            bids = List.copyOf(bids); asks = List.copyOf(asks);
            if (bids.size() != 10 || asks.size() != 10) throw new IllegalArgumentException("Ten levels required");
        }
    }
    public record Minute(String kind, String symbol, Instant start, Instant occurredAt, Instant receivedAt,
            Instant finalizedAt, boolean complete, Map<String, Object> metrics) {
        public Minute { metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics)); }
    }
}
