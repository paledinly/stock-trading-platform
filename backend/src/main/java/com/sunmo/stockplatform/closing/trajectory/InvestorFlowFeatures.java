package com.sunmo.stockplatform.closing.trajectory;

import java.time.*;
import java.util.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.*;

/** Receipt-window changes, not inferred continuous investor trades. */
public final class InvestorFlowFeatures {
    private InvestorFlowFeatures() {}
    public static Map<String, Object> calculate(String symbol, List<Context> rows, Instant cutoff,
            Instant availableBy, Duration maxAge) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String party : List.of("foreign", "institution", "program")) {
            String kind = party.toUpperCase(Locale.ROOT) + "_NET_BUY";
            String scope = party.equals("program") ? "KRX" : "KIS_ESTIMATE_ALL";
            Context current = at(rows, kind, symbol, scope, cutoff, availableBy, maxAge);
            Context base = at(rows, kind, symbol, scope, cutoff.minusSeconds(1800), availableBy, maxAge);
            result.put(party + "NetBuyToday", current == null ? null : current.value());
            result.put(party + "NetBuy30mDelta", current == null || base == null ? null : current.value().subtract(base.value()));
            result.put(party + "Observation", fields("scope", scope, "unit", "SHARES",
                    "status", current == null ? "UNAVAILABLE" : current.status(),
                    "receivedAt", current == null ? null : current.receivedAt(),
                    "sourceAt", current == null ? null : current.sourceAt(),
                    "baselineReceivedAt", base == null ? null : base.receivedAt(),
                    "deltaMeaning", "CHANGE_IN_OBSERVED_CUMULATIVE_VALUES"));
        }
        result.put("retailNetBuyToday", null);
        result.put("historical3d5dStatus", "NOT_COLLECTED");
        return Collections.unmodifiableMap(result);
    }
    private static Context at(List<Context> rows, String kind, String symbol, String scope, Instant at,
            Instant available, Duration maxAge) {
        LocalDate day = available.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        return rows.stream().filter(r -> r.kind().equals(kind) && r.symbol().equals(symbol) && r.scope().equals(scope)
                && r.value() != null && "SHARES".equals(r.unit())
                && r.receivedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().equals(day)
                && !r.receivedAt().isAfter(at) && !r.receivedAt().isAfter(available)
                && !r.receivedAt().isBefore(at.minus(maxAge))
                && (r.sourceAt() == null || !r.sourceAt().isAfter(r.receivedAt())
                    && !r.sourceAt().isBefore(at.minus(maxAge))))
                .max(Comparator.comparing(Context::receivedAt)).orElse(null);
    }
}
