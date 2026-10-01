package com.sunmo.stockplatform.intraday;

import com.sunmo.stockplatform.intraday.IntradayModel.*;
import java.time.*;
import static com.sunmo.stockplatform.intraday.IntradayFeatures.pct;

/** Same tick-ordered fill rules in live paper and replay. Never infer intrabar ordering. */
public final class IntradayTracker {
    private IntradayTracker() {}
    public static void observe(Result result, Input input) {
        Signal s = result.signal(); Outcome o = result.outcome();
        var tick = input.observation().tick();
        Instant at = tick.occurredAt();
        Instant receipt = input.observation().receivedAt();
        advance(result, input.evaluatedAt());
        if (!tick.stockCode().equals(s.stockCode()) || !at.isAfter(s.recommendedAt())
                || receipt.isAfter(input.evaluatedAt()) || at.isAfter(receipt) || o.trackingComplete) return;
        if (o.lastSourceAt != null && (at.isBefore(o.lastSourceAt)
                || at.equals(o.lastSourceAt) && tick.cumulativeVolume() <= o.lastVolume)) return;
        if (at.atZone(IntradayEngine.ZONE).toLocalDate().isAfter(s.recommendedAt().atZone(IntradayEngine.ZONE).toLocalDate())) {
            finishMissing(result); return;
        }
        if (at.isAfter(close(s))) { finishMissing(result); return; }
        if (o.status.equals("ENTRY_READY") && !input.evaluatedAt().isBefore(s.expiresAt())) o.status = "EXPIRED";
        boolean gap = input.dataLoss() || tick.cumulativeVolume() < o.lastVolume
                || Duration.between(receipt, input.evaluatedAt()).compareTo(s.policy().maxDelay()) > 0
                || Duration.between(at, receipt).compareTo(s.policy().maxDelay()) > 0
                || Duration.between(o.lastSourceAt == null ? s.recommendedAt() : o.lastSourceAt, at)
                        .compareTo(s.policy().maxGap()) > 0;
        if (gap) {
            o.quality("OBSERVATION_GAP_OR_DELAY");
            if (o.open()) { o.uncertain = true; o.status = "UNRESOLVED"; o.targetBeforeStop = null; }
        }
        o.lastSourceAt = at; o.lastVolume = tick.cumulativeVolume();
        double price = tick.price().doubleValue();
        o.currentPrice = price; o.currentPriceAt = at;
        double ret = pct(price, s.price());
        o.mfe = Math.max(o.mfe, ret); o.mae = Math.min(o.mae, ret);
        for (int minutes : new int[]{1, 3, 5, 10, 15, 30, 60}) {
            Instant target = s.recommendedAt().plusSeconds(minutes * 60L);
            if (!at.isBefore(target) && !at.isAfter(target.plus(s.policy().maxDelay())) && !gap)
                o.returns.putIfAbsent(minutes, new Observation(minutes, at, price, ret));
        }
        if (tick.tradingHalted()) {
            o.quality("TRADING_HALTED");
            if (o.open()) { o.status = "UNRESOLVED"; o.uncertain = true; }
            return;
        }
        if (o.status.equals("ENTRY_READY")) {
            if (!receipt.isBefore(s.expiresAt())) o.status = "EXPIRED";
            else if (price <= s.plan().stop() || price > s.plan().chaseLimit()) o.status = "INVALIDATED";
            else if (price >= s.plan().entryFrom() && price <= s.plan().entryTo()) {
                double fill = s.policy().costs().configured() ? s.policy().costs().buy(price) : price;
                if (fill <= s.plan().entryTo()) {
                    o.entryPrice = fill; o.entryReferencePrice = price; o.entryAt = receipt; o.status = "ENTERED";
                    o.tradeMfe = 0.0; o.tradeMae = 0.0;
                    if (!s.policy().costs().configured()) o.quality("COSTS_NOT_CONFIGURED");
                }
            }
        } else if (o.status.equals("ENTERED")) {
            o.tradeMfe = Math.max(o.tradeMfe, pct(price, o.entryPrice));
            o.tradeMae = Math.min(o.tradeMae, pct(price, o.entryPrice));
            if (o.trailingStop != null && price <= o.trailingStop) exit(result, receipt, price, "TRAILING_EXIT");
            else if (price <= s.plan().stop()) exit(result, receipt, price, "STOPPED");
            else if (price >= s.plan().target1()) {
                if (s.policy().trailingAfterTarget()) {
                    o.targetBeforeStop = true;
                    o.trailingStop = Math.max(o.trailingStop == null ? s.plan().stop() : o.trailingStop,
                            price - (s.plan().entryTo() - s.plan().stop()));
                } else exit(result, receipt, price, "TARGET_HIT");
            }
            if (o.status.equals("ENTERED") && (!receipt.isBefore(o.entryAt.plus(s.policy().maxHolding()))
                    || !at.isBefore(close(s).minusSeconds(60)))) exit(result, receipt, price, "TIME_EXIT");
        }
        if (at.equals(close(s))) {
            o.closeReturn = ret; o.closeObservedAt = at; o.trackingComplete = true;
        }
        if (o.status.equals("STOPPED") && o.failureFactors.isEmpty()) {
            o.failureFactors.add(s.setup().contains("BREAKOUT") ? "FAKE_BREAKOUT" : "SUPPORT_LOSS");
            var shared = input.observation().feature();
            if (shared != null && shared.vwap() != null && price < shared.vwap().doubleValue())
                o.failureFactors.add("VWAP_LOSS");
            if (tick.tradeStrength() != null && Double.isFinite(s.features().value("strength"))
                    && tick.tradeStrength().doubleValue() < s.features().value("strength") - s.policy().maxStrengthDrop())
                o.failureFactors.add("ORDER_FLOW_REVERSAL");
            o.quality("FAILURE_FACTORS_ARE_DESCRIPTIVE_NOT_CAUSAL");
        }
    }

    public static void advance(Result result, Instant now) {
        if (result.outcome().status.equals("ENTRY_READY") && !now.isBefore(result.signal().expiresAt()))
            result.outcome().status = "EXPIRED";
        if (result.outcome().status.equals("ENTERED")) {
            Instant holdingDeadline = result.outcome().entryAt.plus(result.signal().policy().maxHolding());
            Instant sessionDeadline = close(result.signal()).minusSeconds(60);
            Instant deadline = holdingDeadline.isBefore(sessionDeadline) ? holdingDeadline : sessionDeadline;
            if (now.isAfter(deadline.plus(result.signal().policy().maxDelay()))) {
                result.outcome().status = "UNRESOLVED"; result.outcome().uncertain = true;
                result.outcome().quality("NO_EXECUTABLE_TIME_EXIT");
            }
        }
        if (now.isAfter(close(result.signal()).plus(result.signal().policy().maxDelay()))) finishMissing(result);
    }
    private static void finishMissing(Result result) {
        Outcome o = result.outcome();
        if (o.trackingComplete) return;
        if (o.open()) { o.status = "UNRESOLVED"; o.uncertain = true; o.quality("NO_EXECUTABLE_EXIT"); }
        if (o.closeReturn == null) o.quality("CLOSE_PRICE_MISSING");
        o.trackingComplete = true;
    }
    private static Instant close(Signal signal) {
        return signal.recommendedAt().atZone(IntradayEngine.ZONE).toLocalDate().atTime(15, 30)
                .atZone(IntradayEngine.ZONE).toInstant();
    }
    private static void exit(Result r, Instant at, double price, String status) {
        Outcome o = r.outcome();
        var costs = r.signal().policy().costs();
        o.status = status; o.exitAt = at; o.exitPrice = costs.configured() ? costs.sell(price) : price;
        o.grossReturn = pct(price, o.entryReferencePrice);
        o.netReturn = costs.configured() ? costs.net(o.entryPrice, o.exitPrice) : null;
        if (o.targetBeforeStop == null)
            o.targetBeforeStop = status.equals("TARGET_HIT") ? true : status.equals("STOPPED") ? false : null;
        o.holdingMinutes = Duration.between(o.entryAt, at).toMillis() / 60000.0;
    }
}
