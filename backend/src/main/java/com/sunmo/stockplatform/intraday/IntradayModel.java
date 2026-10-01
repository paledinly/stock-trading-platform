package com.sunmo.stockplatform.intraday;

import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import java.time.Instant;
import java.util.*;

public final class IntradayModel {
    private IntradayModel() {}
    public record Input(ObservedMarketTick observation, Instant evaluatedAt, String stockName,
                        boolean eligible, boolean dataLoss, IntradayProperties policy) {}
    public record Features(Map<String, Double> values, String vwapState, String marketRegime,
                           List<String> unavailable) {
        public Features { values = Map.copyOf(values); unavailable = List.copyOf(unavailable); }
        public double value(String name) { return values.getOrDefault(name, Double.NaN); }
    }
    public record Plan(double entryFrom, double entryTo, double chaseLimit, double stop,
                       double target1, double target2, double riskReward, String invalidCondition) {}
    public record Signal(UUID id, String stockCode, String stockName, String setup, String session,
                         Instant sourceAt, Instant receivedAt, Instant recommendedAt, Instant expiresAt,
                         double price, Plan plan, double score, Features features,
                         List<String> reasons, List<String> risks, IntradayProperties policy) {
        public Signal { reasons = List.copyOf(reasons); risks = List.copyOf(risks); }
    }
    public record Candidate(String stockCode, String stockName, String stage, Instant evaluatedAt,
                            String reason, Features features) {}
    public record Observation(int minutes, Instant observedAt, double price, double returnPercent) {}

    /** Mutable outcome is persisted separately from the immutable signal. No ORM writes to the signal JSON. */
    public static class Outcome {
        public String status = "ENTRY_READY";
        public Instant entryAt;
        public Double entryPrice;
        public Double entryReferencePrice;
        public Instant exitAt;
        public Double exitPrice;
        public Double grossReturn;
        public Double netReturn;
        public Boolean targetBeforeStop;
        public Double trailingStop;
        public Double holdingMinutes;
        public double mfe;
        public double mae;
        public Double tradeMfe;
        public Double tradeMae;
        public Instant lastSourceAt;
        public long lastVolume = -1;
        public Double currentPrice;
        public Instant currentPriceAt;
        public Double closeReturn;
        public Instant closeObservedAt;
        public boolean trackingComplete;
        public boolean uncertain;
        public List<String> quality = new ArrayList<>();
        public List<String> failureFactors = new ArrayList<>();
        public Map<Integer, Observation> returns = new TreeMap<>();
        public void quality(String reason) { if (!quality.contains(reason)) quality.add(reason); }
        public boolean open() { return status.equals("ENTRY_READY") || status.equals("ENTERED"); }
    }
    public record Result(Signal signal, Outcome outcome) {}
}
