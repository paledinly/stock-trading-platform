package com.sunmo.stockplatform.closing.trajectory;

import java.math.BigDecimal;
import java.util.*;
import static com.sunmo.stockplatform.closing.trajectory.MicrostructureModel.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.fields;

public final class MicrostructureFeatures {
    private MicrostructureFeatures() {}
    public static TrajectoryModel.Snapshot enrich(TrajectoryModel.Snapshot s,List<Minute> rows) {
        List<Minute> available=rows.stream().filter(r->r.symbol().equals(s.symbol())
                && !r.start().plusSeconds(60).isAfter(s.timestamp()) && !r.occurredAt().isAfter(s.timestamp())
                && !r.receivedAt().isAfter(s.inputAvailableBy()) && !r.finalizedAt().isAfter(s.inputAvailableBy())).toList();
        Minute book=find(available,"ORDERBOOK",s.timestamp().minusSeconds(60));
        Minute previous=find(available,"ORDERBOOK",s.timestamp().minusSeconds(120));
        Minute trades=find(available,"LARGE_EXECUTION",s.timestamp().minusSeconds(60));
        Map<String,Object> orderbook=book==null?new LinkedHashMap<>():new LinkedHashMap<>(book.metrics());
        orderbook.put("status",book==null?"UNAVAILABLE":book.complete()?"SAMPLED":"PARTIAL_SAMPLES");
        orderbook.put("sourceAt",book==null?null:book.occurredAt());
        orderbook.put("receivedAt",book==null?null:book.receivedAt());
        orderbook.put("finalizedAt",book==null?null:book.finalizedAt());
        for(var mapping:Map.of("bidDepthChange1m","bidDepth10","askDepthChange1m","askDepth10","bidAskRatioChange1m","bidAskDepthRatio").entrySet()) {
            BigDecimal a=book==null?null:number(book.metrics().get(mapping.getValue()));
            BigDecimal b=previous==null?null:number(previous.metrics().get(mapping.getValue()));
            orderbook.put(mapping.getKey(),book!=null && previous!=null && book.complete() && previous.complete() && a!=null && b!=null ? a.subtract(b):null);
        }
        Map<String,Object> execution=new LinkedHashMap<>(s.execution());
        if(trades!=null)execution.putAll(trades.metrics());
        execution.put("largeExecutionObservation",fields("status",trades==null?"UNAVAILABLE":trades.complete()?"OBSERVED":"PARTIAL_OBSERVATION",
                "sourceAt",trades==null?null:trades.occurredAt(),"receivedAt",trades==null?null:trades.receivedAt(),
                "finalizedAt",trades==null?null:trades.finalizedAt()));
        Map<String,Object> availability=new LinkedHashMap<>(s.availability());
        availability.put("orderbook",orderbook.get("status"));
        if(book!=null && availability.get("unavailable") instanceof List<?> missing)
            availability.put("unavailable",missing.stream().filter(x->!"orderbook".equals(x)).toList());
        return new TrajectoryModel.Snapshot(s.symbol(),s.timestamp(),s.evaluatedAt(),s.inputAvailableBy(),"closing-trajectory-v3-microstructure-shadow",
                s.price(),s.volume(),s.turnover(),execution,s.vwap(),s.technical(),s.intradayPattern(),s.market(),s.subScores(),s.risk(),
                orderbook,s.closingAuction(),s.investorFlow(),s.sector(),s.theme(),s.news(),s.disclosures(),availability,s.policy());
    }
    private static Minute find(List<Minute> rows,String kind,java.time.Instant start) {
        return rows.stream().filter(r->r.kind().equals(kind)&&r.start().equals(start)).findFirst().orElse(null);
    }
    private static BigDecimal number(Object value){return value==null?null:new BigDecimal(value.toString());}
}
