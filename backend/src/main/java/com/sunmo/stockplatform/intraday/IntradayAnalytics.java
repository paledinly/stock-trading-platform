package com.sunmo.stockplatform.intraday;

import com.sunmo.stockplatform.intraday.IntradayModel.*;
import java.util.*;
import java.util.function.Function;

public final class IntradayAnalytics {
    private IntradayAnalytics() {}
    public record Stats(int recommendations, int trades, int unresolved, Double winRate, Double averageNetReturn,
                        Double medianNetReturn, Double profitFactor, Double expectancy, Double mfe, Double mae,
                        Double averageHoldingMinutes, Double equalNotionalDrawdown, int consecutiveLosses,
                        Double targetBeforeStopRate, Double randomBaselineTargetRate, Double targetEdge,
                        String confidence) {}
    public static Map<String, Map<String, Stats>> analyze(Collection<Result> rows) {
        Map<String, Map<String, Stats>> result = new LinkedHashMap<>();
        result.put("all", Map.of("ALL", stats(rows)));
        result.put("setup", group(rows, r -> r.signal().setup()));
        result.put("timeBand", group(rows, r -> IntradayEngine.timeBand(r.signal().recommendedAt())));
        result.put("scoreBand", group(rows, r -> {
            double n = r.signal().score();
            return n < 70 ? "<70" : n < 80 ? "70–79" : n < 85 ? "80–84" : n < 90 ? "85–89" : n < 95 ? "90–94" : "95+";
        }));
        result.put("marketRegime", group(rows, r -> r.signal().features().marketRegime()));
        result.put("policy", group(rows, r -> r.signal().policy().toString()));
        return result;
    }
    private static Map<String, Stats> group(Collection<Result> rows, Function<Result, String> key) {
        Map<String, List<Result>> groups = new TreeMap<>();
        rows.forEach(r -> groups.computeIfAbsent(key.apply(r), ignored -> new ArrayList<>()).add(r));
        Map<String, Stats> result = new LinkedHashMap<>(); groups.forEach((k, v) -> result.put(k, stats(v))); return result;
    }
    public static Stats stats(Collection<Result> rows) {
        List<Result> closed = rows.stream().filter(r -> r.outcome().netReturn != null && !r.outcome().uncertain)
                .sorted(Comparator.comparing(r -> r.outcome().exitAt)).toList();
        List<Outcome> trades = closed.stream().map(Result::outcome).toList();
        int unresolved = (int) rows.stream().filter(r -> r.outcome().uncertain || r.outcome().status.equals("UNRESOLVED")).count();
        if (trades.isEmpty()) return new Stats(rows.size(), 0, unresolved, null, null, null, null, null, null, null, null, null, 0, null, null, null, "UNVALIDATED");
        double wins = trades.stream().filter(o -> o.netReturn > 0).count() * 100.0 / trades.size();
        double avg = trades.stream().mapToDouble(o -> o.netReturn).average().orElseThrow();
        double gain = trades.stream().mapToDouble(o -> Math.max(0, o.netReturn)).sum();
        double loss = -trades.stream().mapToDouble(o -> Math.min(0, o.netReturn)).sum();
        double[] sorted = trades.stream().mapToDouble(o -> o.netReturn).sorted().toArray();
        double median = (sorted[(sorted.length - 1) / 2] + sorted[sorted.length / 2]) / 2;
        double equity = 0, peak = 0, drawdown = 0; int streak = 0, maxStreak = 0;
        for (Outcome o : trades) {
            equity += o.netReturn; peak = Math.max(peak, equity); drawdown = Math.max(drawdown, peak - equity);
            streak = o.netReturn < 0 ? streak + 1 : 0; maxStreak = Math.max(maxStreak, streak);
        }
        var ordered = closed.stream().filter(r -> r.outcome().targetBeforeStop != null).toList();
        // Driftless random walk from entry hits the target first with probability stop distance / (stop + target distance).
        Double observed = ordered.isEmpty() ? null : ordered.stream().filter(r -> r.outcome().targetBeforeStop).count() * 100.0 / ordered.size();
        var comparable = ordered.stream().filter(r -> Double.isFinite(randomTargetFirst(r))).toList();
        Double baseline = comparable.isEmpty() ? null : comparable.stream().mapToDouble(IntradayAnalytics::randomTargetFirst).average().orElseThrow() * 100;
        // Edge compares like with like: the observed rate on the same trades the baseline could be computed for.
        Double edge = baseline == null ? null
                : comparable.stream().filter(r -> r.outcome().targetBeforeStop).count() * 100.0 / comparable.size() - baseline;
        return new Stats(rows.size(), trades.size(), unresolved, wins, avg, median, loss == 0 ? null : gain / loss, avg,
                trades.stream().mapToDouble(o -> o.tradeMfe).average().orElse(0),
                trades.stream().mapToDouble(o -> o.tradeMae).average().orElse(0),
                trades.stream().mapToDouble(o -> o.holdingMinutes).average().orElse(0), drawdown, maxStreak,
                observed, baseline, edge, "UNVALIDATED");
    }
    /** Uses the raw entry price the stop/target were compared against; NaN when the geometry is unusable. */
    static double randomTargetFirst(Result r) {
        Outcome o = r.outcome();
        Double entry = o.entryReferencePrice != null ? o.entryReferencePrice : o.entryPrice;
        if (entry == null) return Double.NaN;
        double stop = entry - r.signal().plan().stop(), target = r.signal().plan().target1() - entry;
        return stop > 0 && target > 0 ? stop / (stop + target) : Double.NaN;
    }
}
