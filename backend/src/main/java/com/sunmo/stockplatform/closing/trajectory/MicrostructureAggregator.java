package com.sunmo.stockplatform.closing.trajectory;

import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import com.sunmo.stockplatform.market.domain.MarketTick;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.sunmo.stockplatform.closing.trajectory.MicrostructureModel.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryFeatures.ratio;

/** Sampled books and observed large executions only; no order intent inference. */
public final class MicrostructureAggregator {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private final MicrostructureProperties policy;
    private final Map<String, State> states = new HashMap<>();
    private LocalDate date;
    private long rejected;
    public MicrostructureAggregator(MicrostructureProperties policy) { this.policy = policy; }
    private State state(String kind, String symbol, Instant source, Instant received) {
        LocalDate day = source.atZone(ZONE).toLocalDate();
        LocalTime time = source.atZone(ZONE).toLocalTime();
        if (!day.equals(received.atZone(ZONE).toLocalDate()) || source.isAfter(received)
                || Duration.between(source, received).compareTo(policy.maxObservationGap()) > 0
                || date != null && day.isBefore(date) || time.isBefore(LocalTime.of(9,0)) || !time.isBefore(LocalTime.of(15,20))) {
            rejected++; return null;
        }
        if (!day.equals(date)) { states.clear(); date = day; }
        Instant start = source.truncatedTo(ChronoUnit.MINUTES);
        State s = states.computeIfAbsent(kind + ":" + symbol, k -> new State(kind, symbol));
        if (!received.isBefore(start.plusSeconds(60).plus(policy.watermark()))
                || s.closedThrough != null && !start.isAfter(s.closedThrough)
                || s.lastSource != null && source.isBefore(s.lastSource)) { rejected++; return null; }
        return s;
    }
    public synchronized void book(Book book) {
        State s = state("ORDERBOOK", book.symbol(), book.sourceAt(), book.receivedAt());
        if (s == null) return;
        if (s.lastSource != null && !book.sourceAt().isAfter(s.lastSource)) { rejected++; return; }
        Bucket b = bucket(s, book.sourceAt(), book.receivedAt());
        b.metrics.put("bids", book.bids()); b.metrics.put("asks", book.asks());
        BigDecimal bid = book.bids().getFirst().price(), ask = book.asks().getFirst().price();
        BigDecimal spread = bid.signum() > 0 && ask.compareTo(bid) >= 0 ? ask.subtract(bid) : null;
        b.metrics.put("bestBid", bid); b.metrics.put("bestAsk", ask); b.metrics.put("spread", spread);
        b.metrics.put("spreadPct", spread == null ? null : ratio(spread.multiply(BigDecimal.valueOf(100)), bid));
        for (int n : List.of(1,5,10)) {
            b.metrics.put("bidDepth" + n, depth(book.bids(), n)); b.metrics.put("askDepth" + n, depth(book.asks(), n));
        }
        BigDecimal buy = depth(book.bids(),10), sell = depth(book.asks(),10);
        b.metrics.put("bidAskDepthRatio", ratio(buy,sell));
        b.metrics.put("imbalance", ratio(buy.subtract(sell),buy.add(sell)));
        b.metrics.put("sampling", "REST_SAMPLED_NOT_CONTINUOUS");
        b.metrics.put("sourceDateBasis", "RECEIPT_TRADING_DATE");
        b.metrics.put("spoofingAssessment", "NOT_INFERRED");
    }
    public synchronized void tick(ObservedMarketTick event) {
        var t = event.tick();
        State s = state("LARGE_EXECUTION", t.stockCode(), t.occurredAt(), event.receivedAt());
        if (s == null) return;
        MarketTick previous = s.lastTick;
        if (previous != null && t.cumulativeVolume() <= previous.cumulativeVolume()) { rejected++; return; }
        boolean continuous = previous != null && t.cumulativeVolume() - previous.cumulativeVolume() == t.tradeVolume()
                && Duration.between(previous.occurredAt(),t.occurredAt()).compareTo(policy.maxObservationGap()) <= 0;
        Bucket b = bucket(s,t.occurredAt(),event.receivedAt());
        b.complete &= continuous;
        s.lastTick = t;
        b.metrics.put("largeTradeThreshold", policy.largeTradeThreshold());
        b.metrics.put("amountUnit", "KRW"); b.metrics.put("directionMethod", "CONTIGUOUS_CUMULATIVE_VOLUME_DELTAS");
        for (String side : List.of("Buy","Sell","Unknown")) {
            b.metrics.putIfAbsent("large" + side + "ExecutionCount1m",0L);
            b.metrics.putIfAbsent("large" + side + "ExecutionAmount1m",BigDecimal.ZERO);
        }
        BigDecimal amount = t.price().multiply(BigDecimal.valueOf(t.tradeVolume()));
        if (amount.compareTo(policy.largeTradeThreshold()) < 0) return;
        String side = "Unknown";
        if (continuous && previous.cumulativeBuyVolume() != null && previous.cumulativeSellVolume() != null
                && t.cumulativeBuyVolume() != null && t.cumulativeSellVolume() != null) {
            long buy = t.cumulativeBuyVolume() - previous.cumulativeBuyVolume();
            long sell = t.cumulativeSellVolume() - previous.cumulativeSellVolume();
            if (buy == t.tradeVolume() && sell == 0) side = "Buy";
            else if (sell == t.tradeVolume() && buy == 0) side = "Sell";
        }
        String countKey = "large" + side + "ExecutionCount1m", amountKey = "large" + side + "ExecutionAmount1m";
        b.metrics.put(countKey,(Long)b.metrics.get(countKey)+1);
        b.metrics.put(amountKey,((BigDecimal)b.metrics.get(amountKey)).add(amount));
    }
    private Bucket bucket(State s, Instant source, Instant received) {
        Instant start = source.truncatedTo(ChronoUnit.MINUTES);
        Bucket b = s.buckets.computeIfAbsent(start,k -> new Bucket(source));
        if (s.lastSource != null && Duration.between(s.lastSource,source).compareTo(policy.maxObservationGap()) > 0) b.complete=false;
        b.lastSource=source; b.lastReceived=received; b.samples++; s.lastSource=source;
        // Bounded retry queue. Eviction is reported, never converted into a zero-activity minute.
        while(s.buckets.size()>3) { s.buckets.pollFirstEntry(); rejected++; }
        return b;
    }
    public synchronized List<Minute> ready(Instant now) {
        List<Minute> rows=new ArrayList<>();
        for(State s:states.values()) for(var entry:s.buckets.entrySet()) {
            Instant start=entry.getKey(); Bucket b=entry.getValue();
            if(start.plusSeconds(60).plus(policy.watermark()).isAfter(now)) continue;
            boolean complete=b.complete && Duration.between(start,b.firstSource).compareTo(policy.maxObservationGap())<=0
                    && Duration.between(b.lastSource,start.plusSeconds(60)).compareTo(policy.maxObservationGap())<=0;
            Map<String,Object> metrics=new LinkedHashMap<>(b.metrics); metrics.put("sampleCount",b.samples);
            if(s.kind.equals("ORDERBOOK")) complete &= b.samples>=2;
            rows.add(new Minute(s.kind,s.symbol,start,b.lastSource,b.lastReceived,now,complete,metrics));
            s.closedThrough=start;
        }
        return rows;
    }
    public synchronized void acknowledge(List<Minute> rows) {
        rows.forEach(r -> {State s=states.get(r.kind()+":"+r.symbol());if(s!=null)s.buckets.remove(r.start());});
    }
    public synchronized long rejected(){return rejected;}
    private static BigDecimal depth(List<Level> levels,int n){return levels.subList(0,n).stream().map(Level::quantity).reduce(BigDecimal.ZERO,BigDecimal::add);}
    private static class State {
        final String kind,symbol; Instant lastSource,closedThrough; MarketTick lastTick;
        final TreeMap<Instant,Bucket> buckets=new TreeMap<>();
        State(String kind,String symbol){this.kind=kind;this.symbol=symbol;}
    }
    private static class Bucket {
        final Instant firstSource; Instant lastSource,lastReceived; long samples; boolean complete=true;
        final Map<String,Object> metrics=new LinkedHashMap<>();
        Bucket(Instant source){firstSource=source;}
    }
}
