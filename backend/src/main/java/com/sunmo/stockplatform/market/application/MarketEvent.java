package com.sunmo.stockplatform.market.application;

import java.time.Instant;
import java.util.Map;

public record MarketEvent(String id, String type, Instant occurredAt, int version, String correlationId,
        Map<String, Object> payload) {
}
