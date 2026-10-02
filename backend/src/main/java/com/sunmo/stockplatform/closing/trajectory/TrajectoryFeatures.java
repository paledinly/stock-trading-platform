package com.sunmo.stockplatform.closing.trajectory;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.*;

/** Pure point-in-time computation. Percent fields use percentage points; ratios are unscaled. */
public final class TrajectoryFeatures {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private TrajectoryFeatures() {}
    public static BigDecimal ratio(BigDecimal a, BigDecimal b) {
        return a == null || b == null || b.signum() <= 0 ? null : a.divide(b, 8, RoundingMode.HALF_UP);
    }
    public static BigDecimal pct(BigDecimal a, BigDecimal b) {
        BigDecimal r = ratio(a, b); return r == null ? null : r.subtract(BigDecimal.ONE).movePointRight(2);
    }
    public static BigDecimal range(BigDecimal price, BigDecimal high, BigDecimal low) {
        return price == null || high == null || low == null ? null : ratio(price.subtract(low), high.subtract(low));
    }
    public static BigDecimal relative(BigDecimal stock, BigDecimal market) {
        return stock == null || market == null ? null : stock.subtract(market);
    }
    public static BigDecimal acceleration(BigDecimal recent, BigDecimal previous, BigDecimal older) {
        return recent == null || previous == null || older == null ? null
                : ratio(recent.subtract(previous.multiply(BigDecimal.valueOf(2))).add(older), older);
    }
    public static BigDecimal rankChange(BigDecimal current, BigDecimal old) { return relative(old, current); }
    static boolean positive(BigDecimal n) { return n != null && n.signum() > 0; }
    static boolean negative(BigDecimal n) { return n != null && n.signum() < 0; }
    static BigDecimal vwap(Minute row) { return ratio(row.dailyTurnover(), row.dailyVolume()); }

    public static Snapshot calculate(String symbol, String marketName, Instant cutoff, Instant evaluatedAt,
            List<Minute> source, List<Context> contexts, List<StockCandle> dailySource, TrajectoryProperties policy) {
        if (cutoff.isAfter(evaluatedAt)) throw new IllegalArgumentException("Future feature cutoff");
        LocalDate date = cutoff.atZone(ZONE).toLocalDate();
        List<Minute> rows = source.stream().filter(r -> r.symbol().equals(symbol)
                && r.start().atZone(ZONE).toLocalDate().equals(date) && !r.start().plusSeconds(60).isAfter(cutoff)
                && !r.occurredAt().isAfter(cutoff) && !r.receivedAt().isAfter(evaluatedAt)
                && !r.finalizedAt().isAfter(evaluatedAt)).sorted(Comparator.comparing(Minute::start)).toList();
        if (rows.isEmpty() || !rows.getLast().start().plusSeconds(60).equals(cutoff)) return null;
        Minute last = rows.getLast();
        List<StockCandle> daily = dailySource.stream().filter(r -> r.isFinalCandle() && r.getTimeframe().equals("1D")
                && r.getStartTime().atZone(ZONE).toLocalDate().isBefore(date)
                && r.getCreatedAt() != null && r.getUpdatedAt() != null
                && !r.getCreatedAt().isAfter(evaluatedAt) && !r.getUpdatedAt().isAfter(evaluatedAt))
                .sorted(Comparator.comparing(StockCandle::getStartTime)).toList();
        BigDecimal prevClose = daily.isEmpty() ? null : daily.getLast().getClose();
        BigDecimal priceReturn = pct(last.close(), prevClose);
        Map<String, Object> volumes = windows(rows, cutoff, "volume", Minute::volume, policy.volumeComparisonWindows());
        Map<String, Object> money = windows(rows, cutoff, "turnover", Minute::turnover, policy.turnoverComparisonWindows());
        volumes.put("dailyVolume", last.dailyVolume()); money.put("dailyTurnover", last.dailyTurnover());
        volumes.put("volumeVsYesterday", daily.isEmpty() ? null : ratio(last.dailyVolume(), BigDecimal.valueOf(daily.getLast().getVolume())));
        for (int n : List.of(5, 20)) volumes.put("volumeVs" + n + "dAvg", ratio(last.dailyVolume(), averageDaily(daily, n, r -> BigDecimal.valueOf(r.getVolume()))));
        for (int n : List.of(5, 15)) money.put("turnoverAcceleration" + n + "m", acceleration(
                sum(rows, cutoff, n, Minute::turnover), sum(rows, cutoff.minusSeconds(n * 60L), n, Minute::turnover),
                sum(rows, cutoff.minusSeconds(n * 120L), n, Minute::turnover)));
        Context rank = context(contexts, "TURNOVER_RANK", symbol, "ALL", cutoff, evaluatedAt, policy.contextMaxAge());
        money.put("turnoverRank", rank == null ? null : rank.value());
        money.put("rankScope", "ALL_KRX_PROVIDER_RESPONSE"); money.put("rankReceivedAt", rank == null ? null : rank.receivedAt());
        for (int n : List.of(5, 15, 30, 60)) {
            Context old = context(contexts, "TURNOVER_RANK", symbol, "ALL", cutoff.minusSeconds(n * 60L), evaluatedAt, policy.contextMaxAge());
            money.put("turnoverRank" + n + "mAgo", old == null ? null : old.value());
            money.put("turnoverRankChange" + n + "m", rank == null || old == null ? null : rankChange(rank.value(), old.value()));
        }
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("executionStrength", last.executionStrength());
        execution.put("buyExecutionVolume", last.buyVolume()); execution.put("sellExecutionVolume", last.sellVolume());
        execution.put("buySellExecutionRatio", ratio(last.buyVolume(), last.sellVolume()));
        Map<String, Object> vw = new LinkedHashMap<>();
        vw.put("vwap", vwap(last)); vw.put("vwapDistancePct", pct(last.close(), vwap(last)));
        vw.put("priceAboveVwap", vwap(last) == null ? null : last.close().compareTo(vwap(last)) > 0);
        for (int n : List.of(5, 15)) {
            BigDecimal strength = sum(rows, cutoff, n, Minute::executionStrength);
            execution.put("executionStrength" + n + "mAvg", strength == null ? null : ratio(strength, BigDecimal.valueOf(n)));
            execution.put("executionStrengthSlope" + n + "m", slope(window(rows, cutoff, n), Minute::executionStrength));
            execution.put("buySellRatio" + n + "m", ratio(sum(rows, cutoff, n, Minute::buyVolume), sum(rows, cutoff, n, Minute::sellVolume)));
            vw.put("vwapDistanceSlope" + n + "m", slope(window(rows, cutoff, n), r -> pct(r.close(), vwap(r))));
        }
        List<Minute> run = trailingContinuous(rows);
        Instant cross = null; int above = 0; boolean failed = false;
        for (int i = 0; i < run.size(); i++) {
            Minute r = run.get(i); BigDecimal d = pct(r.close(), vwap(r));
            if (d == null) { above = 0; cross = null; continue; }
            if (d.signum() > 0) {
                above++;
                if (i > 0 && pct(run.get(i - 1).close(), vwap(run.get(i - 1))) != null
                        && pct(run.get(i - 1).close(), vwap(run.get(i - 1))).signum() <= 0) cross = r.start().plusSeconds(60);
            } else { failed = above > 0; above = 0; }
        }
        vw.put("vwapCrossTime", cross); vw.put("vwapAboveDurationMinutes", above);
        vw.put("state", vwap(last) == null ? "UNKNOWN" : above > 1 ? "VWAP_ABOVE_HOLD"
                : above == 1 && cross != null ? "VWAP_BREAKOUT" : above == 1 ? "VWAP_ABOVE_OBSERVED"
                : failed ? "VWAP_FAILED_BREAKOUT" : "VWAP_BELOW");
        Instant lateStart = date.atTime(policy.analysisStart()).atZone(ZONE).toInstant();
        List<Minute> late = rows.stream().filter(r -> !r.start().isBefore(lateStart)).toList();
        boolean lateComplete = !late.isEmpty() && late.getFirst().start().equals(lateStart)
                && late.stream().allMatch(Minute::complete) && contiguous(late);
        Map<String, Object> patterns = patterns(lateComplete ? late : List.of(), policy.pullbackPercent());
        BigDecimal lateReturn = lateComplete ? pct(last.close(), late.getFirst().open()) : null;
        BigDecimal high = lateComplete ? late.stream().map(Minute::high).max(BigDecimal::compareTo).orElse(null) : null;
        Map<String, Object> price = fields("currentPrice", last.close(), "open", last.dayOpen(), "high", last.dayHigh(), "low", last.dayLow(),
                "prevClose", prevClose, "changePct", priceReturn, "changeFromOpenPct", pct(last.close(), last.dayOpen()),
                "dayRangePosition", range(last.close(), last.dayHigh(), last.dayLow()),
                "distanceFromHighPct", positive(last.dayHigh()) ? pct(last.close(), last.dayHigh()).negate() : null,
                "distanceFromLowPct", pct(last.close(), last.dayLow()), "lateSessionReturnPct", lateReturn,
                "highAfter1430", high, "newHighAfter1430", patterns.get("newHighAfter1430"),
                "highBreakTime", patterns.get("highBreakTime"), "pullbackDepthPct", patterns.get("pullbackDepthPct"));
        Context index = context(contexts, "INDEX", marketName, marketName, cutoff, evaluatedAt, policy.contextMaxAge());
        Map<String, Object> market = new LinkedHashMap<>();
        market.put("name", marketName); market.put("marketReturnPct", index == null ? null : index.returnPct());
        market.put("receivedAt", index == null ? null : index.receivedAt());
        market.put("relativeStrengthMarket", index == null ? null : relative(priceReturn, index.returnPct()));
        for (int n : List.of(30, 60)) {
            Context old = context(contexts, "INDEX", marketName, marketName, cutoff.minusSeconds(n * 60L), evaluatedAt, policy.contextMaxAge());
            market.put("marketReturn" + n + "mPct", index == null || old == null ? null : pct(index.value(), old.value()));
        }
        Map<String, Object> technical = daily(daily, last.close());
        technical.put("upperWickRatio", last.dayHigh() == null || last.dayOpen() == null || last.dayLow() == null ? null
                : ratio(last.dayHigh().subtract(last.dayOpen().max(last.close())), last.dayHigh().subtract(last.dayLow())));
        BigDecimal lateBuy = lateComplete && late.stream().allMatch(r -> r.buyVolume() != null)
                ? late.stream().map(Minute::buyVolume).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        BigDecimal lateSell = lateComplete && late.stream().allMatch(r -> r.sellVolume() != null)
                ? late.stream().map(Minute::sellVolume).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        technical.put("lateSessionSellRatio", lateBuy == null || lateSell == null ? null : ratio(lateSell, lateBuy.add(lateSell)));
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("LateMoneyFlowScore", score((BigDecimal) money.get("turnover15mChangePct"), "turnover15mChangePct"));
        scores.put("ClosingStrengthScore", score(lateReturn, "lateSessionReturnPct"));
        scores.put("ExecutionMomentumScore", score((BigDecimal) execution.get("executionStrengthSlope15m"), "executionStrengthSlope15m"));
        scores.put("VWAPStrengthScore", score((BigDecimal) vw.get("vwapDistanceSlope15m"), "vwapDistanceSlope15m"));
        scores.put("MarketRelativeStrengthScore", score((BigDecimal) market.get("relativeStrengthMarket"), "relativeStrengthMarket"));
        for (String key : List.of("OrderbookPressureScore", "SectorStrengthScore", "ThemeBreadthScore", "CatalystQualityScore", "OvernightRiskScore")) scores.put(key, score(null, "NOT_COLLECTED"));
        scores.put("OverheatRiskScore", score((BigDecimal) technical.get("return5d"), "return5d"));
        return new Snapshot(symbol, cutoff, evaluatedAt, evaluatedAt, "closing-trajectory-v2-flow-shadow", price, volumes, money, execution, vw,
                technical, patterns, market, scores, fields("catalystKnown", false, "catalystType", "UNKNOWN", "confidence", "UNVALIDATED"),
                Map.of(), Map.of(), InvestorFlowFeatures.calculate(symbol, contexts, cutoff, evaluatedAt, policy.contextMaxAge()), Map.of(), Map.of(), List.of(), List.of(),
                fields("latestMinuteComplete", last.complete(), "lateSessionComplete", lateComplete, "dailyCount", daily.size(),
                        "investorFlow", "SEE_PER_PARTY_OBSERVATION_STATUS",
                        "unavailable", List.of("orderbook", "closingAuction", "sector", "theme", "news", "disclosures")), policy);
    }

    static Map<String, Object> score(BigDecimal x, String reason) {
        // Bounded signed evidence scale, not calibrated probability and never a production ranking input.
        Double value = x == null ? null : 50 + 50 * Math.tanh(x.doubleValue() / 10);
        return fields("value", value, "input", x, "reason", reason, "formula", "50+50*tanh(input/10)", "status", "SHADOW_UNVALIDATED");
    }
    static Context context(List<Context> rows, String kind, String symbol, String scope, Instant at, Instant available, Duration age) {
        return rows.stream().filter(r -> r.kind().equals(kind) && r.symbol().equals(symbol) && r.scope().equals(scope)
                && !r.receivedAt().isAfter(at) && !r.receivedAt().isAfter(available)
                && !r.receivedAt().isBefore(at.minus(age))
                && r.receivedAt().atZone(ZONE).toLocalDate().equals(at.atZone(ZONE).toLocalDate()))
                .max(Comparator.comparing(Context::receivedAt)).orElse(null);
    }
    static Map<String, Object> windows(List<Minute> rows, Instant end, String key, Function<Minute, BigDecimal> value, List<Integer> periods) {
        Map<String, Object> result = new LinkedHashMap<>();
        Set<Integer> windows = new TreeSet<>(periods); windows.add(1);
        for (int n : windows) {
            BigDecimal recent = sum(rows, end, n, value);
            result.put(key + n + "m", recent);
            if (n > 1) result.put(key + n + "mChangePct", pct(recent, sum(rows, end.minusSeconds(n * 60L), n, value)));
        }
        return result;
    }
    static List<Minute> window(List<Minute> rows, Instant end, int minutes) {
        List<Minute> subset = rows.stream().filter(r -> !r.start().isBefore(end.minusSeconds(minutes * 60L)) && r.start().isBefore(end)).toList();
        return subset.size() == minutes && subset.stream().allMatch(Minute::complete) && contiguous(subset) ? subset : List.of();
    }
    static BigDecimal sum(List<Minute> rows, Instant end, int n, Function<Minute, BigDecimal> field) {
        List<Minute> w = window(rows, end, n);
        return w.isEmpty() || w.stream().anyMatch(r -> field.apply(r) == null) ? null : w.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    public static BigDecimal slope(List<Minute> rows, Function<Minute, BigDecimal> field) {
        if (rows.size() < 2 || rows.stream().anyMatch(r -> field.apply(r) == null)) return null;
        double meanX = (rows.size() - 1) / 2.0, meanY = rows.stream().map(field).mapToDouble(BigDecimal::doubleValue).average().orElseThrow();
        double numerator = 0, denominator = 0;
        for (int i = 0; i < rows.size(); i++) { numerator += (i - meanX) * (field.apply(rows.get(i)).doubleValue() - meanY); denominator += (i - meanX) * (i - meanX); }
        return BigDecimal.valueOf(numerator / denominator).setScale(8, RoundingMode.HALF_UP);
    }
    static boolean contiguous(List<Minute> rows) {
        for (int i = 1; i < rows.size(); i++) if (!rows.get(i - 1).start().plusSeconds(60).equals(rows.get(i).start())) return false;
        return true;
    }
    static List<Minute> trailingContinuous(List<Minute> rows) {
        int start = rows.size();
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (!rows.get(i).complete() || (i < rows.size() - 1 && !rows.get(i).start().plusSeconds(60).equals(rows.get(i + 1).start()))) break;
            start = i;
        }
        return rows.subList(start, rows.size());
    }
    public static Map<String, Object> patterns(List<Minute> rows, BigDecimal pullbackThreshold) {
        if (rows.size() < 3) return fields("state", "INSUFFICIENT_DATA", "lateBreakout", null, "pullbackRecovery", null,
                "failedBreakout", null, "distribution", null, "newHighAfter1430", null);
        BigDecimal peak = rows.getFirst().high(), deepest = BigDecimal.ZERO; Instant breakAt = null;
        boolean pulled = false, recovered = false, supported = false; int pullbackAt = -1;
        for (int i = 1; i < rows.size(); i++) {
            Minute r = rows.get(i), prev = rows.get(i - 1);
            if (prev.dayHigh() != null && r.high().compareTo(prev.dayHigh()) > 0) breakAt = r.start().plusSeconds(60);
            // Minute OHLC cannot establish intra-minute ordering: only earlier minute peaks establish pullbacks.
            BigDecimal depth = pct(r.low(), peak).negate(); deepest = deepest.max(depth);
            if (depth.compareTo(pullbackThreshold) >= 0) {
                if (!pulled) pullbackAt = i;
                pulled = true; supported |= vwap(r) != null && r.close().compareTo(vwap(r)) >= 0;
            }
            if (pulled && i > pullbackAt && supported && r.close().compareTo(peak.multiply(new BigDecimal("0.995"))) >= 0
                    && r.volume() != null && prev.volume() != null && r.volume().compareTo(prev.volume()) > 0) recovered = true;
            peak = peak.max(r.high());
        }
        Minute last = rows.getLast(); Instant end = last.start().plusSeconds(60);
        BigDecimal moneyChange = pct(sum(rows, end, 5, Minute::turnover), sum(rows, end.minusSeconds(300), 5, Minute::turnover));
        BigDecimal priceChange = rows.size() < 6 ? null : pct(last.close(), rows.get(rows.size() - 6).close());
        BigDecimal sellChange = pct(sum(rows, end, 5, Minute::sellVolume), sum(rows, end.minusSeconds(300), 5, Minute::sellVolume));
        BigDecimal distance = pct(last.close(), vwap(last));
        BigDecimal strengthSlope = slope(window(rows, end, 5), Minute::executionStrength);
        boolean recentBreak = breakAt != null && !breakAt.isBefore(end.minusSeconds(300));
        boolean crossedBelowAfterBreak = false;
        for (int i = 1; i < rows.size(); i++) {
            Minute r = rows.get(i), previous = rows.get(i - 1);
            BigDecimal before = pct(previous.close(), vwap(previous)), after = pct(r.close(), vwap(r));
            if (breakAt != null && !r.start().plusSeconds(60).isBefore(breakAt)
                    && positive(before) && after != null && after.signum() <= 0) crossedBelowAfterBreak = true;
        }
        return fields("state", "OBSERVED", "lateBreakout", moneyChange == null || distance == null || priceChange == null ? null
                        : recentBreak && positive(moneyChange) && positive(distance) && positive(priceChange),
                "pullbackOccurred", pulled, "pullbackRecovery", recovered, "recoveredFromPullback", recovered,
                "pullbackDepthPct", deepest, "highBreakTime", breakAt, "newHighAfter1430", breakAt != null,
                "failedBreakout", priceChange == null || strengthSlope == null || distance == null ? null
                        : recentBreak && crossedBelowAfterBreak && negative(priceChange) && negative(strengthSlope) && negative(distance),
                "distribution", moneyChange == null || priceChange == null || sellChange == null ? null
                        : positive(moneyChange) && negative(priceChange) && positive(sellChange));
    }
    static BigDecimal averageDaily(List<StockCandle> rows, int n, Function<StockCandle, BigDecimal> field) {
        if (rows.size() < n) return null;
        List<BigDecimal> values = rows.subList(rows.size() - n, rows.size()).stream().map(field).toList();
        return values.contains(null) ? null : ratio(values.stream().reduce(BigDecimal.ZERO, BigDecimal::add), BigDecimal.valueOf(n));
    }
    static Map<String, Object> daily(List<StockCandle> rows, BigDecimal price) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int n : List.of(1, 3, 5, 10, 20)) out.put("return" + n + "d", rows.size() < n ? null : pct(price, rows.get(rows.size() - n).getClose()));
        for (int n : List.of(5, 10, 20)) {
            var w = rows.size() < n ? List.<StockCandle>of() : rows.subList(rows.size() - n, rows.size());
            out.put("high" + n + "d", w.stream().map(StockCandle::getHigh).max(BigDecimal::compareTo).orElse(null));
            out.put("low" + n + "d", w.stream().map(StockCandle::getLow).min(BigDecimal::compareTo).orElse(null));
            out.put("ma" + n, averageDaily(rows, n, StockCandle::getClose));
            out.put("avgVolume" + n + "d", averageDaily(rows, n, r -> BigDecimal.valueOf(r.getVolume())));
            out.put("avgTurnover" + n + "d", averageDaily(rows, n, StockCandle::getTradingValue));
            if (n >= 10) {
                Double volatility = null;
                if (rows.size() >= n + 1) {
                    List<Double> returns = new ArrayList<>();
                    for (int i = rows.size() - n; i < rows.size(); i++) returns.add(pct(rows.get(i).getClose(), rows.get(i - 1).getClose()).doubleValue());
                    double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
                    volatility = Math.sqrt(returns.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / (n - 1));
                }
                out.put("volatility" + n + "d", volatility);
            }
        }
        out.put("distanceFrom20dHighPct", pct(price, (BigDecimal) out.get("high20d")));
        out.put("high52w", null); out.put("distanceFrom52wHighPct", null);
        out.put("historyBasis", "PRIOR_COMPLETED_DAILY_CANDLES");
        return out;
    }
}
