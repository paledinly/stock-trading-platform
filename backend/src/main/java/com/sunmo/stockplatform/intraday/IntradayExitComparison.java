package com.sunmo.stockplatform.intraday;

import com.sunmo.stockplatform.intraday.IntradayModel.*;
import java.time.Instant;
import java.time.Duration;
import java.util.*;

/** Read-only fixed-candidate experiments; entry is frozen unless explicitly varied. */
public final class IntradayExitComparison {
    public record Variant(String name, double stopDistanceMultiplier, double targetDistanceMultiplier,
                          Double protectAtPercent, Double protectFloorPercent, IntradayExperimentOptions options) {
        public Variant(String name, double stopDistanceMultiplier, double targetDistanceMultiplier,
                       Double protectAtPercent, Double protectFloorPercent) {
            this(name, stopDistanceMultiplier, targetDistanceMultiplier, protectAtPercent, protectFloorPercent, null);
        }
        public Variant {
            options = options == null ? IntradayExperimentOptions.none() : options;
            if (name == null || name.isBlank() || name.equals("BASELINE") || name.length() > 80
                    || !Double.isFinite(stopDistanceMultiplier) || stopDistanceMultiplier <= 0
                    || !Double.isFinite(targetDistanceMultiplier) || targetDistanceMultiplier <= 0
                    || (protectAtPercent == null) != (protectFloorPercent == null))
                throw new IllegalArgumentException("Invalid exit variant");
            if (protectAtPercent != null && (!Double.isFinite(protectAtPercent) || !Double.isFinite(protectFloorPercent)
                    || protectAtPercent <= 0 || protectFloorPercent < 0 || protectFloorPercent >= protectAtPercent))
                throw new IllegalArgumentException("Invalid protection");
            if (options.trailingDistancePercent() != null && protectAtPercent == null && options.partialAtPercent() == null)
                throw new IllegalArgumentException("Trailing requires a protection or partial-profit trigger");
        }
    }
    public record Request(List<Variant> variants, IntradayProperties.Costs costs, Instant cohortBoundary) {
        public Request {
            variants = variants == null ? List.of() : List.copyOf(variants);
            if (variants.size() > 12 || variants.stream().map(Variant::name).distinct().count() != variants.size()
                    || costs != null && !costs.configured()) throw new IllegalArgumentException("Invalid comparison request");
        }
    }
    private static final class Trial {
        final Result paper;
        final Variant variant;
        Result replay;
        Result partial;
        boolean anchored;
        boolean reentryChecked;
        Instant aboveSince;
        boolean aboveSeen, retested;
        Double initialRiskPercent;
        final IntradayExperimentOptions options;
        Trial(Result paper, Variant variant) {
            this.paper = paper; this.variant = variant;
            options = variant == null ? IntradayExperimentOptions.none() : variant.options();
            replay = new Result(paper.signal(), new Outcome());
        }
        void accept(Input input, List<Trial> peers) {
            if (!input.observation().tick().stockCode().equals(paper.signal().stockCode())) return;
            boolean valid = usable(input);
            if (valid && !reentryChecked && replay.outcome().status.equals("ENTRY_READY")
                    && input.evaluatedAt().isBefore(paper.signal().expiresAt())) {
                reentryChecked = true;
                var stopped = peers.stream().filter(t -> t != this && t.replay.outcome().exitAt != null
                        && !t.replay.outcome().exitAt.isAfter(paper.signal().recommendedAt())
                        && t.replay.outcome().status.equals("STOPPED") && !t.replay.outcome().uncertain).toList();
                if (options.maxStoppedPerSymbol() > 0 && stopped.size() >= options.maxStoppedPerSymbol()) {
                    skip("REENTRY_STOP_LIMIT"); return;
                }
                if (options.requireFreshStructureAfterStop() && !stopped.isEmpty()) {
                    Trial last = stopped.stream().max(Comparator.comparing(t -> t.replay.outcome().exitAt)).orElseThrow();
                    if (!structureAvailable(paper.signal()) || !structureAvailable(last.paper.signal())) {
                        invalid("REENTRY_REFERENCE_UNAVAILABLE"); return;
                    }
                    if (!freshStructure(paper.signal(), last.paper.signal())) {
                        skip("REENTRY_STRUCTURE_OR_FLOW_NOT_RENEWED"); return;
                    }
                }
            }
            boolean allowEntry = !options.changesEntry() || entryAllowed(input, valid);
            // Altered exits can overlap later saved candidates; never invent simultaneous same-symbol entry.
            if (options.changesEntry() && valid && replay.outcome().status.equals("ENTRY_READY")
                    && peers.stream().anyMatch(t -> t != this && t.paper.signal().recommendedAt().isBefore(paper.signal().recommendedAt())
                    && t.replay.outcome().status.equals("ENTERED"))) allowEntry = false;
            if (partial != null) IntradayTracker.observe(partial, input, false, false);
            IntradayTracker.observe(replay, input, allowEntry, replay.signal().policy().trailingAfterTarget());
            Outcome o = replay.outcome();
            if (!anchored && o.entryAt != null) {
                anchored = options.changesEntry() || o.entryAt.equals(paper.outcome().entryAt)
                        && Objects.equals(o.entryReferencePrice, paper.outcome().entryReferencePrice)
                        && Objects.equals(o.entryPrice, paper.outcome().entryPrice);
                if (!anchored) { invalid("ENTRY_NOT_REPRODUCIBLE"); return; }
                if (variant != null) {
                    Signal s = paper.signal(); Plan p = s.plan(); double entry = o.entryPrice;
                    double stop = entry - (entry - p.stop()) * variant.stopDistanceMultiplier();
                    double target = entry + (p.target1() - entry) * variant.targetDistanceMultiplier();
                    if (options.narrowStopMode().equals("WIDEN"))
                        stop = Math.min(stop, entry * (1 - options.minimumStopPercent() / 100));
                    stop = options.round(s.stockCode(), stop, false);
                    target = options.round(s.stockCode(), target, true);
                    if (!Double.isFinite(stop) || !Double.isFinite(target)) { invalid("PRICE_GRID_UNAVAILABLE"); return; }
                    if (stop <= 0 || stop >= entry || target <= entry) {
                        invalid("INVALID_EXIT_GEOMETRY"); return;
                    }
                    initialRiskPercent = (entry - stop) / entry * 100;
                    Plan changed = new Plan(p.entryFrom(), p.entryTo(), p.chaseLimit(), stop, target,
                            p.target2(), (target - entry) / (entry - stop), p.invalidCondition());
                    replay = new Result(withPlan(s, changed), o);
                    if (options.partialAtPercent() != null) {
                        double partialTarget = options.round(s.stockCode(), entry * (1 + options.partialAtPercent() / 100), true);
                        if (partialTarget >= target) { invalid("PARTIAL_TARGET_NOT_BELOW_FINAL_TARGET"); return; }
                        Plan partialPlan = new Plan(p.entryFrom(), p.entryTo(), p.chaseLimit(), stop, partialTarget,
                                p.target2(), changed.riskReward(), p.invalidCondition());
                        Outcome leg = new Outcome(); leg.status = "ENTERED";
                        leg.entryAt = o.entryAt; leg.entryPrice = o.entryPrice; leg.entryReferencePrice = o.entryReferencePrice;
                        leg.tradeMfe = 0.; leg.tradeMae = 0.; leg.lastSourceAt = o.lastSourceAt; leg.lastVolume = o.lastVolume;
                        partial = new Result(withPlan(s, partialPlan), leg);
                    }
                }
                if (initialRiskPercent == null) initialRiskPercent = (o.entryPrice - paper.signal().plan().stop()) / o.entryPrice * 100;
                return; // Entry tick cannot also trigger an exit/protection.
            }
            if (anchored && variant != null && variant.protectAtPercent() != null && o.status.equals("ENTERED")
                    && o.tradeMfe >= variant.protectAtPercent()) {
                double floor = options.round(paper.signal().stockCode(), o.entryPrice * (1 + variant.protectFloorPercent() / 100), false);
                o.trailingStop = Math.max(o.trailingStop == null ? replay.signal().plan().stop() : o.trailingStop, floor);
            }
            if (anchored && valid && o.status.equals("ENTERED") && options.trailingDistancePercent() != null
                    && (variant.protectAtPercent() != null && o.tradeMfe >= variant.protectAtPercent()
                        || partial != null && partial.outcome().status.equals("TARGET_HIT"))) {
                double trail = options.round(paper.signal().stockCode(), input.observation().tick().price().doubleValue()
                        * (1 - options.trailingDistancePercent() / 100), false);
                o.trailingStop = Math.max(o.trailingStop == null ? replay.signal().plan().stop() : o.trailingStop, trail);
            }
            if (o.trailingStop != null && !options.tickSizes().isEmpty())
                o.trailingStop = options.round(paper.signal().stockCode(), o.trailingStop, false);
            if (partial != null && valid && partial.outcome().status.equals("ENTERED") && o.trailingStop != null)
                partial.outcome().trailingStop = o.trailingStop;
        }
        boolean usable(Input input) {
            var tick = input.observation().tick(); var at = tick.occurredAt(); var receipt = input.observation().receivedAt();
            Outcome o = replay.outcome(); var p = paper.signal().policy();
            return at.isAfter(paper.signal().recommendedAt()) && !at.isAfter(receipt) && !receipt.isAfter(input.evaluatedAt())
                    && !input.dataLoss() && !input.observation().dataLoss() && !tick.tradingHalted()
                    && Duration.between(at, receipt).compareTo(p.maxDelay()) <= 0
                    && Duration.between(receipt, input.evaluatedAt()).compareTo(p.maxDelay()) <= 0
                    && Duration.between(o.lastSourceAt == null ? paper.signal().recommendedAt() : o.lastSourceAt, at).compareTo(p.maxGap()) <= 0
                    && (o.lastSourceAt == null || !at.isBefore(o.lastSourceAt)
                        && tick.cumulativeVolume() >= o.lastVolume && (!at.equals(o.lastSourceAt) || tick.cumulativeVolume() > o.lastVolume));
        }
        boolean entryAllowed(Input input, boolean valid) {
            if (!valid || !replay.outcome().status.equals("ENTRY_READY")
                    || !input.evaluatedAt().isBefore(paper.signal().expiresAt())) return false;
            double price = input.observation().tick().price().doubleValue();
            if (options.minimumStopPercent() > 0 && options.narrowStopMode().equals("FILTER")) {
                var s = paper.signal(); double fill = s.policy().costs().configured() ? s.policy().costs().buy(price) : price;
                double stop = options.round(s.stockCode(), fill - (fill - s.plan().stop()) * variant.stopDistanceMultiplier(), false);
                if (!Double.isFinite(stop)) { invalid("PRICE_GRID_UNAVAILABLE"); return false; }
                if (price >= s.plan().entryFrom() && price <= s.plan().entryTo()
                        && (fill - stop) / fill * 100 < options.minimumStopPercent()) {
                    skip("STOP_TOO_NARROW"); return false;
                }
            }
            if (options.entryMode().equals("IMMEDIATE") || !paper.signal().setup().contains("BREAKOUT")) return true;
            double level = resistance(paper.signal());
            if (!Double.isFinite(level)) { invalid("BREAKOUT_REFERENCE_UNAVAILABLE"); return false; }
            Instant at = input.observation().tick().occurredAt();
            if (options.entryMode().equals("HOLD")) {
                if (price <= level) { aboveSince = null; return false; }
                if (aboveSince == null) aboveSince = at;
                return Duration.between(aboveSince, at).getSeconds() >= options.holdSeconds();
            }
            // Retest needs three distinct observations: above -> near the line -> reclaim.
            if (price < level * (1 - options.retestTolerancePercent() / 100)) {
                aboveSeen = false; retested = false; return false;
            }
            boolean reclaim = retested && price > level * (1 + options.retestTolerancePercent() / 100);
            if (aboveSeen && Math.abs(price / level - 1) * 100 <= options.retestTolerancePercent()) retested = true;
            if (price > level) aboveSeen = true;
            return reclaim;
        }
        void skip(String reason) {
            replay.outcome().status = "FILTERED"; replay.outcome().trackingComplete = true; replay.outcome().quality(reason);
        }
        void invalid(String reason) {
            replay.outcome().status = "UNRESOLVED"; replay.outcome().uncertain = true;
            replay.outcome().trackingComplete = true; replay.outcome().quality(reason);
        }
    }
    private static Signal withPlan(Signal s, Plan p) {
        return new Signal(s.id(), s.stockCode(), s.stockName(), s.setup(), s.session(), s.sourceAt(), s.receivedAt(),
                s.recommendedAt(), s.expiresAt(), s.price(), p, s.score(), s.features(), s.reasons(), s.risks(), s.policy());
    }
    static double resistance(Signal s) {
        double stored = s.features().value("setupResistance");
        if (Double.isFinite(stored)) return stored;
        // Legacy BREAKOUT support == resistance; invert its recorded, unmodified plan formula.
        if (!s.setup().equals("BREAKOUT")) return Double.NaN;
        double range = (s.plan().entryTo() - s.price()) / s.policy().entryRangeFraction();
        return range > 0 ? s.plan().stop() + range * s.policy().stopBufferFraction() : Double.NaN;
    }
    private static boolean freshStructure(Signal current, Signal previous) {
        return resistance(current) > resistance(previous)
                && current.features().value("volumeRatio5m") > previous.features().value("volumeRatio5m")
                && current.features().value("valueRatio5m") > previous.features().value("valueRatio5m");
    }
    private static boolean structureAvailable(Signal signal) {
        return Double.isFinite(resistance(signal)) && Double.isFinite(signal.features().value("volumeRatio5m"))
                && Double.isFinite(signal.features().value("valueRatio5m"));
    }
    private final Request request;
    private final List<Result> paper;
    private final Map<String, List<Trial>> trials = new LinkedHashMap<>();
    private final Map<String, Map<String, List<Trial>>> bySymbol = new HashMap<>();
    private final boolean candidateMode;
    public IntradayExitComparison(List<Result> paper, Request request) {
        this.paper = List.copyOf(paper); this.request = request;
        candidateMode = request.variants().stream().anyMatch(v -> v.options().changesEntry());
        var universe = paper.stream().filter(r -> candidateMode || r.outcome().entryAt != null)
                .sorted(Comparator.comparing(r -> r.signal().recommendedAt())).toList();
        trials.put("BASELINE", universe.stream().map(r -> new Trial(r, null)).toList());
        for (Variant v : request.variants()) trials.put(v.name(), universe.stream().map(r -> new Trial(r, v)).toList());
        trials.forEach((name, group) -> {
            Map<String, List<Trial>> symbols = new HashMap<>();
            group.forEach(t -> symbols.computeIfAbsent(t.paper.signal().stockCode(), ignored -> new ArrayList<>()).add(t));
            bySymbol.put(name, symbols);
        });
    }
    public void accept(Input input) {
        Input observed = input.observation().dataLoss() && !input.dataLoss()
                ? new Input(input.observation(), input.evaluatedAt(), input.stockName(), input.eligible(), true, input.policy()) : input;
        bySymbol.values().forEach(symbols -> {
            var peers = symbols.getOrDefault(observed.observation().tick().stockCode(), List.of());
            peers.forEach(t -> t.accept(observed, peers));
        });
    }
    public Map<String, Object> finish(Instant asOf) {
        for (var group : trials.values()) for (Trial t : group) {
            IntradayTracker.advance(t.replay, asOf);
            if (!t.anchored && !t.options.changesEntry() && t.paper.outcome().entryAt != null) t.invalid("ENTRY_NOT_REPRODUCIBLE");
            Outcome o = t.replay.outcome();
            if (t.partial != null) {
                IntradayTracker.advance(t.partial, asOf);
                Outcome leg = t.partial.outcome();
                if (leg.uncertain || o.uncertain) {
                    t.invalid("PARTIAL_PATH_UNRESOLVED"); o.grossReturn = null; o.netReturn = null;
                } else if (complete(t.replay) && complete(t.partial)) {
                    double weight = t.options.partialFraction();
                    o.grossReturn = weight * leg.grossReturn + (1 - weight) * o.grossReturn;
                    o.netReturn = leg.netReturn == null || o.netReturn == null ? null : weight * leg.netReturn + (1 - weight) * o.netReturn;
                    o.exitPrice = weight * leg.exitPrice + (1 - weight) * o.exitPrice;
                    o.quality("PARTIAL_WEIGHTED_RETURN_FINAL_REMAINDER_STATUS");
                }
            }
            if (request.costs() != null && o.grossReturn != null) {
                // Shared cost sensitivity on the SAME raw entry/exit; never rerun entry admission.
                double rawExit = o.entryReferencePrice * (1 + o.grossReturn / 100);
                o.netReturn = request.costs().net(request.costs().buy(o.entryReferencePrice), request.costs().sell(rawExit));
            }
        }
        Set<UUID> common = new HashSet<>();
        trials.get("BASELINE").stream().filter(t -> complete(t.replay)).forEach(t -> common.add(t.paper.signal().id()));
        trials.values().forEach(group -> group.stream().filter(t -> !complete(t.replay)).forEach(t -> common.remove(t.paper.signal().id())));
        Map<String, Object> comparisons = new LinkedHashMap<>();
        trials.forEach((name, group) -> {
            var results = group.stream().map(t -> t.replay).toList();
            var paired = results.stream().filter(r -> common.contains(r.signal().id())).toList();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("results", results); details.put("cohorts", cohorts(results));
            details.put("analytics", IntradayAnalytics.analyze(results)); details.put("commonCompleted", summary(paired));
            details.put("partialExits", group.stream().filter(t -> t.partial != null).map(t -> Map.of(
                    "id", t.paper.signal().id(), "fraction", t.options.partialFraction(), "outcome", t.partial.outcome())).toList());
            details.put("riskUnits", group.stream().filter(t -> complete(t.replay) && t.initialRiskPercent != null && t.initialRiskPercent > 0)
                    .map(t -> Map.of("id", t.paper.signal().id(), "initialRiskPercent", t.initialRiskPercent,
                            "grossRiskUnits", t.replay.outcome().grossReturn / t.initialRiskPercent)).toList());
            details.put("missedBaselineWinners", trials.get("BASELINE").stream().filter(t -> complete(t.replay)
                    && t.replay.outcome().grossReturn > 0 && results.stream().anyMatch(r -> r.signal().id().equals(t.paper.signal().id())
                    && r.outcome().entryAt == null && !r.outcome().uncertain && !r.outcome().open())).count());
            comparisons.put(name, details);
        });
        return Map.of("mode", candidateMode ? "FIXED_CANDIDATE_ENTRY_EXIT_COMPARISON" : "FIXED_ENTRY_EXIT_COMPARISON", "asOf", asOf, "request", request,
                "paperCohorts", cohorts(paper), "commonCompletedCount", common.size(), "comparisons", comparisons,
                "limitations", List.of("CAPTURED_UNIVERSE_ONLY", "NO_OOS_PROFITABILITY_CLAIM",
                        "PRICE_GRID_IS_EXPLICIT_RESEARCH_ASSUMPTION", "FRACTIONAL_POSITION_NO_LOT_OR_CAPITAL_MODEL", "COST_OVERRIDE_IS_FIXED_RAW_FILL_SENSITIVITY",
                        "AFTER_EXIT_GAPS_DO_NOT_INVALIDATE_COMPLETED_TRADES"));
    }
    private static boolean complete(Result r) {
        return r.outcome().exitAt != null && r.outcome().grossReturn != null && !r.outcome().uncertain;
    }
    private static Map<String, Object> summary(List<Result> rows) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", rows.size()); result.put("net", IntradayAnalytics.stats(rows));
        result.put("averageGrossReturn", rows.stream().mapToDouble(r -> r.outcome().grossReturn).average().stream().boxed().findFirst().orElse(null));
        result.put("targetHitRate", rows.isEmpty() ? null : rows.stream().filter(r -> Boolean.TRUE.equals(r.outcome().targetBeforeStop)).count() * 100.0 / rows.size());
        return result;
    }
    private Map<String, Long> cohorts(List<Result> rows) {
        Map<String, Long> counts = new TreeMap<>();
        for (Result r : rows) {
            Outcome o = r.outcome();
            counts.merge("status:" + o.status, 1L, Long::sum);
            counts.merge("cost:" + (request.costs() != null || r.signal().policy().costs().configured() ? "CONFIGURED" : "MISSING"), 1L, Long::sum);
            counts.merge("quality:" + (o.uncertain ? "UNCERTAIN" : complete(r) ? "COMPLETED" : o.entryAt == null ? "NO_ENTRY" : "OPEN"), 1L, Long::sum);
            if (request.cohortBoundary() != null) counts.merge("entryPeriod:" + (o.entryAt == null ? "NO_ENTRY"
                    : o.entryAt.isBefore(request.cohortBoundary()) ? "BEFORE" : "AFTER"), 1L, Long::sum);
        }
        return counts;
    }
}
