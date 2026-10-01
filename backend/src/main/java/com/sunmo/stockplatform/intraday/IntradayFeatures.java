package com.sunmo.stockplatform.intraday;

import com.sunmo.stockplatform.intraday.IntradayModel.*;
import java.time.*;
import java.util.*;

/** Cumulative windows from already collected ticks; VWAP is supplied by the common market engine. */
public final class IntradayFeatures {
    private IntradayFeatures() {}
    public static final List<String> UNAVAILABLE = List.of("DAY_RETURN_PREVIOUS_CLOSE", "SAME_TIME_RVOL",
            "KOSPI_KOSDAQ_SECTOR_RS", "MARKET_REGIME", "BID_ASK_SPREAD", "ORDER_IMBALANCE",
            "VI_STATE_COUNT", "PRICE_LIMIT_DISTANCE", "INVESTMENT_WARNING", "LISTING_AGE");

    public static Features calculate(List<Input> history, Input now) {
        Instant cutoff = now.observation().tick().occurredAt();
        List<Input> rows = history.stream().filter(x -> !x.observation().tick().occurredAt().isAfter(cutoff)
                && !x.observation().receivedAt().isAfter(now.evaluatedAt())
                && x.observation().tick().businessDate().equals(now.observation().tick().businessDate())).toList();
        Map<String, Double> values = new LinkedHashMap<>();
        var tick = now.observation().tick();
        var shared = now.observation().feature();
        double price = tick.price().doubleValue();
        values.put("price", price);
        if (tick.cumulativeTradingValue() != null) values.put("dailyValue", tick.cumulativeTradingValue().doubleValue());
        if (tick.tradeStrength() != null) values.put("strength", tick.tradeStrength().doubleValue());
        String vwapState = "UNAVAILABLE";
        // The common engine has a price fallback: require the cumulative source before treating it as VWAP.
        if (shared != null && shared.occurredAt().equals(cutoff) && shared.vwap() != null
                && tick.cumulativeTradingValue() != null && tick.cumulativeTradingValue().signum() > 0
                && tick.cumulativeVolume() > 0) {
            values.put("vwap", shared.vwap().doubleValue());
            values.put("vwapDistance", pct(price, shared.vwap().doubleValue()));
            vwapState = price >= shared.vwap().doubleValue() ? "ABOVE" : "BELOW";
        }
        for (int minutes : new int[]{1, 3, 5, 10, 15, 30}) {
            Input base = at(rows, cutoff.minusSeconds(minutes * 60L), now.policy().maxGap());
            if (base == null) continue;
            var before = base.observation().tick();
            values.put("return" + minutes + "m", pct(price, before.price().doubleValue()));
            long volume = tick.cumulativeVolume() - before.cumulativeVolume();
            if (volume < 0) continue;
            values.put("volume" + minutes + "m", (double) volume);
            if (tick.cumulativeTradingValue() != null && before.cumulativeTradingValue() != null) {
                double value = tick.cumulativeTradingValue().subtract(before.cumulativeTradingValue()).doubleValue();
                if (value >= 0) values.put("value" + minutes + "m", value);
            }
            Input previous = at(rows, cutoff.minusSeconds(minutes * 120L), now.policy().maxGap());
            if (previous != null) {
                long previousVolume = before.cumulativeVolume() - previous.observation().tick().cumulativeVolume();
                if (previousVolume > 0) values.put("volumeRatio" + minutes + "m", (double) volume / previousVolume);
                if (before.cumulativeTradingValue() != null && previous.observation().tick().cumulativeTradingValue() != null) {
                    double previousValue = before.cumulativeTradingValue()
                            .subtract(previous.observation().tick().cumulativeTradingValue()).doubleValue();
                    if (previousValue > 0 && values.containsKey("value" + minutes + "m"))
                        values.put("valueRatio" + minutes + "m", values.get("value" + minutes + "m") / previousValue);
                }
            }
            if (minutes == 5 && before.tradeStrength() != null && tick.tradeStrength() != null)
                values.put("strengthChange5m", tick.tradeStrength().subtract(before.tradeStrength()).doubleValue());
        }
        if (values.containsKey("return5m") && values.containsKey("return10m"))
            values.put("momentumAcceleration", values.get("return5m")
                    - ((1 + values.get("return10m") / 100) / (1 + values.get("return5m") / 100) - 1) * 100);
        if (values.containsKey("volumeRatio1m") && values.containsKey("volumeRatio5m"))
            values.put("volumeAcceleration", values.get("volumeRatio1m") - values.get("volumeRatio5m"));
        if (tick.highPrice() != null && tick.highPrice().signum() > 0)
            values.put("highDistance", pct(price, tick.highPrice().doubleValue()));
        return new Features(values, vwapState, "UNAVAILABLE", UNAVAILABLE);
    }

    static Input at(List<Input> history, Instant cutoff, Duration tolerance) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Input row = history.get(i);
            Instant source = row.observation().tick().occurredAt();
            if (!source.isAfter(cutoff)) return Duration.between(source, cutoff).compareTo(tolerance) <= 0 ? row : null;
        }
        return null;
    }
    public static double pct(double price, double base) { return (price / base - 1) * 100; }
}
