package com.sunmo.stockplatform.closing.trajectory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class TrajectoryModel {
    private TrajectoryModel() {}
    public record Minute(String symbol, Instant start, Instant occurredAt, Instant receivedAt, Instant finalizedAt,
            String phase, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
            BigDecimal volume, BigDecimal turnover, BigDecimal dailyVolume, BigDecimal dailyTurnover,
            BigDecimal dayOpen, BigDecimal dayHigh, BigDecimal dayLow, BigDecimal executionStrength,
            BigDecimal buyVolume, BigDecimal sellVolume, boolean complete) {}
    /** REST observations use receipt time; no exchange timestamp is invented. Scope prevents mixing rank universes. */
    public record Context(String kind, String symbol, String scope, Instant receivedAt,
            BigDecimal value, BigDecimal returnPct) {}
    public record Snapshot(String symbol, Instant timestamp, Instant evaluatedAt, Instant inputAvailableBy, String version,
            Map<String, Object> price, Map<String, Object> volume, Map<String, Object> turnover,
            Map<String, Object> execution, Map<String, Object> vwap, Map<String, Object> technical,
            Map<String, Object> intradayPattern, Map<String, Object> market,
            Map<String, Object> subScores, Map<String, Object> risk,
            Map<String, Object> orderbook, Map<String, Object> closingAuction,
            Map<String, Object> investorFlow, Map<String, Object> sector, Map<String, Object> theme,
            List<Object> news, List<Object> disclosures, Map<String, Object> availability,
            TrajectoryProperties policy) {
        public Snapshot completedAt(Instant completed) {
            return new Snapshot(symbol, timestamp, completed, inputAvailableBy, version, price, volume, turnover, execution,
                    vwap, technical, intradayPattern, market, subScores, risk, orderbook, closingAuction,
                    investorFlow, sector, theme, news, disclosures, availability, policy);
        }
    }
    public static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(result);
    }
}
