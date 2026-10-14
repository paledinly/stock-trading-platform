/*
 * 호가 샘플과 체결 틱을 종목별 미세구조 1분 집계(호가 깊이·스프레드·불균형, 큰 체결 매수/매도/불명 건수·금액)로 메모리에서 묶는 집계기예요.
 * 원천시각이 수신시각보다 미래이거나, 너무 늦게 왔거나, 이미 닫힌 분에 속한 관측은 거부해요. 확정된 분은 고치지 않아요.
 * MicrostructureService가 만들어 사용해요.
 */
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
/*
 * (샘플링한 호가와 관측된 큰 체결만 다뤄요. 주문 의도(허수 주문 등)는 추정하지 않아요.)
 * 1분짜리 관찰 바구니를 종류(ORDERBOOK/LARGE_EXECUTION)·종목마다 두고, 1분+대기시간이 지나면 닫는 방식이에요.
 * 바구니에 들어온 사진·기록이 듬성듬성하면 "불완전(complete=false)" 딱지를 붙여요.
 */
public final class MicrostructureAggregator {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private final MicrostructureProperties policy;
    private final Map<String, State> states = new HashMap<>();
    private LocalDate date;
    private long rejected;
    /** 하는 일: 설정을 받아 집계기를 만들어요. 받는 것: 미세구조 설정. 돌려주는 것: 집계기예요. */
    public MicrostructureAggregator(MicrostructureProperties policy) { this.policy = policy; }
    /**
     * 하는 일: 관측이 받아도 되는 것인지 검사하고 해당 종류·종목 상태를 돌려줘요. 날짜가 바뀌면 이전 상태를 비워요.
     * 받는 것: 종류, 종목, 원천시각, 수신시각이에요.
     * 돌려주는 것: 상태, 또는 거부 시 null이에요(날짜 불일치, 미래 원천시각, 지연 과다, 장 시간 밖, 닫힌 분, 시간 역순).
     */
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
    /**
     * 하는 일: 호가 샘플 하나로 1분 바구니의 호가 지표(최우선 매수·매도가, 스프레드, 1/5/10단계 잔량, 잔량 비율·불균형)를 갱신해요.
     * 같은 분에 여러 샘플이 오면 마지막 샘플 값으로 덮어써요. 받는 것: Book. 돌려주는 것: 없어요.
     */
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
    /**
     * 하는 일: 체결 틱 하나를 보고, 금액이 기준(기본 1억 원) 이상이면 큰 체결로 세요.
     * 직전 틱과 누적 매수/매도 체결량 증가분이 이번 체결량과 정확히 맞을 때만 Buy/Sell로, 아니면 Unknown으로 분류해요.
     * 받는 것: 체결 이벤트. 돌려주는 것: 없어요.
     */
    public synchronized void tick(ObservedMarketTick event) {
        var t = event.tick();
        State s = state("LARGE_EXECUTION", t.stockCode(), t.occurredAt(), event.receivedAt());
        if (s == null) return;
        MarketTick previous = s.lastTick;
        if (previous != null && t.cumulativeVolume() <= previous.cumulativeVolume()) { rejected++; return; }
        boolean continuous = !event.dataLoss() && previous != null && t.cumulativeVolume() - previous.cumulativeVolume() == t.tradeVolume()
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
    /**
     * 하는 일: 원천시각이 속한 1분 바구니를 꺼내거나 만들고, 직전 관측과 간격이 너무 길면 불완전 표시를 해요.
     * 받는 것: 상태, 원천시각, 수신시각이에요. 돌려주는 것: 바구니예요. 미저장 바구니가 3개를 넘으면 오래된 것부터 버려요.
     */
    private Bucket bucket(State s, Instant source, Instant received) {
        Instant start = source.truncatedTo(ChronoUnit.MINUTES);
        Bucket b = s.buckets.computeIfAbsent(start,k -> new Bucket(source));
        if (s.lastSource != null && Duration.between(s.lastSource,source).compareTo(policy.maxObservationGap()) > 0) b.complete=false;
        b.lastSource=source; b.lastReceived=received; b.samples++; s.lastSource=source;
        // Bounded retry queue. Eviction is reported, never converted into a zero-activity minute.
        while(s.buckets.size()>3) { s.buckets.pollFirstEntry(); rejected++; }
        return b;
    }
    /**
     * 하는 일: 분 끝+대기시간이 지난 바구니를 미세구조 1분 집계로 만들어 돌려주고 그 분을 닫아요.
     * 분 시작·끝 근처까지 관측이 있어야 완전하고, 호가는 샘플이 2개 이상이어야 완전해요.
     * 받는 것: 현재 시각이에요. 돌려주는 것: Minute 목록이에요(저장 후 acknowledge로 지워요).
     */
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
    /** 하는 일: DB에 저장된 1분 집계를 메모리 바구니에서 지워요. 받는 것: 저장된 Minute 목록. 돌려주는 것: 없어요. */
    public synchronized void acknowledge(List<Minute> rows) {
        rows.forEach(r -> {State s=states.get(r.kind()+":"+r.symbol());if(s!=null)s.buckets.remove(r.start());});
    }
    /** 하는 일: 거부하거나 버린 관측·분 개수를 돌려줘요. */
    public synchronized long rejected(){return rejected;}
    /** 하는 일: 앞에서부터 n단계 호가 잔량을 더해요. 받는 것: 호가 단계 목록, n. 돌려주는 것: 잔량 합이에요. */
    private static BigDecimal depth(List<Level> levels,int n){return levels.subList(0,n).stream().map(Level::quantity).reduce(BigDecimal.ZERO,BigDecimal::add);}
    /** 종류·종목 하나의 상태예요. 마지막 원천시각, 닫힌 마지막 분, 마지막 체결 틱, 미저장 바구니들을 담아요. */
    private static class State {
        final String kind,symbol; Instant lastSource,closedThrough; MarketTick lastTick;
        final TreeMap<Instant,Bucket> buckets=new TreeMap<>();
        State(String kind,String symbol){this.kind=kind;this.symbol=symbol;}
    }
    /** 1분 바구니예요. 첫·마지막 원천시각, 마지막 수신시각, 샘플 수, 완전 여부, 지표 묶음을 담아요. */
    private static class Bucket {
        final Instant firstSource; Instant lastSource,lastReceived; long samples; boolean complete=true;
        final Map<String,Object> metrics=new LinkedHashMap<>();
        Bucket(Instant source){firstSource=source;}
    }
}
