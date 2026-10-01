package com.sunmo.stockplatform.intraday;

import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.intraday.IntradayModel.*;
import java.time.*;
import java.util.*;

/** Pure deterministic strategy shared by paper trading and receipt-time replay. */
public final class IntradayEngine {
    public static final ZoneId ZONE = ClosingTradingCalendar.ZONE;
    private final ClosingTradingCalendar calendar;
    private final Map<String, Deque<Input>> history = new HashMap<>();
    private final Map<String, Instant> evaluatedMinute = new HashMap<>();
    private final Map<String, Candidate> candidates = new TreeMap<>();
    private final Map<UUID, Result> results = new LinkedHashMap<>();
    public IntradayEngine(ClosingTradingCalendar calendar) { this.calendar = calendar; }
    public Collection<Result> results() { return List.copyOf(results.values()); }
    public Collection<Candidate> candidates() { return List.copyOf(candidates.values()); }
    public void restore(Result result) { results.put(result.signal().id(), result); }

    public Signal accept(Input input) {
        var tick = input.observation().tick();
        Instant at = tick.occurredAt(); Instant received = input.observation().receivedAt();
        Instant evaluated = input.evaluatedAt(); var p = input.policy(); String code = tick.stockCode();
        if (at.isAfter(received) || received.isAfter(evaluated) || !calendar.isTradingDay(tick.businessDate())) return null;
        for (Result result : results.values()) IntradayTracker.observe(result, input);
        Deque<Input> window = history.computeIfAbsent(code, ignored -> new ArrayDeque<>());
        Input previous = window.peekLast();
        if (previous != null) {
            var last = previous.observation().tick();
            if (at.isBefore(last.occurredAt()) || at.equals(last.occurredAt())
                    && tick.cumulativeVolume() <= last.cumulativeVolume()) return null;
            if (!tick.businessDate().equals(last.businessDate()) || input.dataLoss()
                    || Duration.between(last.occurredAt(), at).compareTo(p.maxGap()) > 0
                    || tick.cumulativeVolume() < last.cumulativeVolume()
                    || tick.cumulativeTradingValue() != null && last.cumulativeTradingValue() != null
                       && tick.cumulativeTradingValue().compareTo(last.cumulativeTradingValue()) < 0) window.clear();
        }
        window.addLast(input);
        while (!window.isEmpty() && window.getFirst().observation().tick().occurredAt().isBefore(at.minusSeconds(3660)))
            window.removeFirst();
        if (window.size() > p.maxHistoryTicksPerStock()) {
            window.clear(); window.add(input);
            candidate(input, "SCANNED", "HISTORY_CAPACITY_WARMUP_RESET", IntradayFeatures.calculate(List.of(input), input));
            return null;
        }
        // ponytail: retain exact ticks for rolling extrema; bounded subscribed universe, not whole-market ticks.
        Instant minute = evaluated.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        if (minute.equals(evaluatedMinute.put(code, minute))) return null;
        List<Input> rows = List.copyOf(window);
        Features features = IntradayFeatures.calculate(rows, input);
        String session = session(evaluated);
        String gate = null;
        // Preserve the recorded v1 decision rule during receipt replay/recovery.
        if (p.version().equals("intraday-research-v1") && !p.costs().configured()) gate = "COSTS_NOT_CONFIGURED";
        else if (!input.eligible() || tick.tradingHalted()) gate = "UNIVERSE_EXCLUDED";
        else if (session == null) gate = "OUTSIDE_RECOMMENDATION_SESSION";
        else if (Duration.between(at, evaluated).compareTo(p.maxDelay()) > 0 || input.dataLoss()) gate = "STALE_OR_LOST_INPUT";
        else if (p.requireCompleteRiskData()) gate = "RISK_DATA_UNAVAILABLE";
        else if (IntradayFeatures.at(rows, at.minusSeconds(1800), p.maxGap()) == null) gate = "WARMUP_30M_REQUIRED";
        else if (!Double.isFinite(features.value("vwap"))) gate = "VWAP_UNAVAILABLE";
        if (gate != null) { candidate(input, "SCANNED", gate, features); return null; }
        if (!(features.value("dailyValue") >= p.minDailyValue())
                || !(features.value("value5m") >= p.minFiveMinuteValue())) {
            candidate(input, "SCANNED", "LOW_LIQUIDITY", features); return null;
        }
        double volumeFloor = p.minVolumeRatio() * p.sessionVolumeMultipliers().get(session);
        if (!(features.value("volumeRatio5m") >= volumeFloor)
                || !(features.value("valueRatio5m") >= p.minValueRatio()) || features.value("vwapDistance") < 0) {
            candidate(input, "WATCHLIST", "FLOW_CONFIRMATION_PENDING", features); return null;
        }
        if (features.value("vwapDistance") > p.maxVwapDistance()
                || features.value("return5m") > p.maxFiveMinuteReturn()
                || features.value("volumeRatio5m") > p.maxVolumeRatio()
                || features.value("strengthChange5m") < -p.maxStrengthDrop()) {
            candidate(input, "WATCHLIST", "CHASE_OR_ORDER_FLOW_REVERSAL", features); return null;
        }
        var latest = IntradaySetups.segment(rows, at.minusSeconds(300), at.plusNanos(1));
        if (latest != null && latest.high() > latest.low()
                && (latest.high() - Math.max(latest.open(), latest.close())) / (latest.high() - latest.low()) > p.maxUpperWick()) {
            candidate(input, "WATCHLIST", "UPPER_WICK", features); return null;
        }
        var detection = IntradaySetups.detect(rows, input, features);
        if (detection == null) { candidate(input, "SETUP_FORMING", "ORDERED_SETUP_PENDING", features); return null; }
        Plan plan = IntradaySetups.plan(detection, tick.price().doubleValue(), p);
        if (plan == null) { candidate(input, "SETUP_FORMING", "NET_RISK_REWARD_INSUFFICIENT", features); return null; }
        boolean duplicate = results.values().stream().anyMatch(r -> r.signal().stockCode().equals(code)
                && (r.outcome().open() || r.signal().setup().equals(detection.setup())
                    && evaluated.isBefore(r.signal().recommendedAt().plus(p.cooldown()))));
        if (duplicate) { candidate(input, "WATCHLIST", "ACTIVE_OR_COOLDOWN", features); return null; }
        List<String> reasons = new ArrayList<>(detection.reasons());
        reasons.add(String.format(Locale.ROOT, "최근 5분 거래량 %.2f배 · 거래대금 %.2f배", features.value("volumeRatio5m"), features.value("valueRatio5m")));
        reasons.add("누적 원천 VWAP 상회; " + (p.costs().configured() ? "비용 포함" : "비용 미반영 가격 기준")
                + " 최악 진입 R:R " + String.format(Locale.ROOT, "%.2f", plan.riskReward()));
        List<String> risks = new ArrayList<>(features.unavailable());
        risks.add("UNVALIDATED_RESEARCH_SCORE"); risks.add("SPREAD_IS_COST_ASSUMPTION_NOT_OBSERVED");
        if (!p.costs().configured()) risks.add("COSTS_NOT_CONFIGURED");
        if (!Double.isFinite(features.value("strength"))) risks.add("ORDER_FLOW_UNAVAILABLE");
        // Equal bottleneck margins, not fitted weights or an estimated probability. Setup-specific structural gate above.
        double score = 50 * Math.min(2, Math.min(features.value("volumeRatio5m") / volumeFloor,
                Math.min(features.value("valueRatio5m") / p.minValueRatio(), plan.riskReward() / p.minRiskReward())));
        Instant expires = evaluated.plusSeconds(p.expiryMinutes().get(detection.setup()) * 60L);
        Instant cutoff = tick.businessDate().atTime(15, 0).atZone(ZONE).toInstant();
        if (expires.isAfter(cutoff)) expires = cutoff;
        UUID id = UUID.nameUUIDFromBytes((code + "|" + evaluated + "|" + detection.setup() + "|" + p.version())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Signal signal = new Signal(id, code, input.stockName(), detection.setup(), session, at, received,
                evaluated, expires, tick.price().doubleValue(), plan, score, features, reasons, risks, p);
        results.put(id, new Result(signal, new Outcome()));
        candidate(input, "ENTRY_READY", "RECOMMENDED", features);
        return signal;
    }

    public void advance(Instant now) { results.values().forEach(r -> IntradayTracker.advance(r, now)); }
    private void candidate(Input input, String stage, String reason, Features features) {
        candidates.put(input.observation().tick().stockCode(), new Candidate(input.observation().tick().stockCode(),
                input.stockName(), stage, input.evaluatedAt(), reason, features));
    }
    public static String session(Instant at) {
        LocalTime time = at.atZone(ZONE).toLocalTime();
        if (time.isBefore(LocalTime.of(9, 5)) || !time.isBefore(LocalTime.of(15, 0))) return null;
        return time.isBefore(LocalTime.of(10, 0)) ? "OPENING" : time.isBefore(LocalTime.of(14, 0)) ? "MID_SESSION" : "LATE_SESSION";
    }
    public static String timeBand(Instant at) {
        int minute = at.atZone(ZONE).getHour() * 60 + at.atZone(ZONE).getMinute();
        return minute < 600 ? "09:05–10:00" : minute < 660 ? "10:00–11:00" : minute < 780 ? "11:00–13:00"
                : minute < 840 ? "13:00–14:00" : "14:00–15:00";
    }
}
