/*
 * 저장된 미세구조 1분 집계를 Trajectory 스냅샷에 덧붙이는 계산 도구 클래스예요.
 * 기준시각 직전 1분의 호가 지표와 그 앞 1분 대비 변화, 큰 체결 지표를 붙이고, 기준시각·입력 마감 이후 자료는 쓰지 않아요.
 * MicrostructureService.enrich가 사용해요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import java.math.BigDecimal;
import java.util.*;
import static com.sunmo.stockplatform.closing.trajectory.MicrostructureModel.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.fields;

/**
 * 스냅샷에 미세구조 정보를 끼워 넣는 계산 도구예요.
 * 요약표에 "마지막 1분 동안 진열대 모습" 사진을 붙이되, 마감 시각 뒤에 찍힌 사진은 절대 붙이지 않는 편집자와 같아요.
 */
public final class MicrostructureFeatures {
    /** 도구 클래스라 객체를 만들지 못하게 막아 둔 생성자예요. */
    private MicrostructureFeatures() {}
    /**
     * 하는 일: 기준시각 전에 끝나고 입력 마감 전에 받은·확정된 1분 집계만 골라, 직전 1분 호가·큰 체결 지표와 1분 변화량을 스냅샷에 넣어요.
     * 받는 것: 원래 스냅샷, 미세구조 1분 집계 목록이에요.
     * 돌려주는 것: orderbook·execution·availability가 채워진 새 스냅샷(버전 v3-microstructure-shadow)이에요. 자료가 없으면 UNAVAILABLE이에요.
     */
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
        // 두 분이 모두 완전할 때만 10단계 잔량·잔량 비율의 1분 변화량을 계산해요.
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
    /** 하는 일: 종류와 분 시작이 일치하는 1분 집계를 찾아요. 받는 것: 목록, 종류, 분 시작. 돌려주는 것: Minute 또는 null이에요. */
    private static Minute find(List<Minute> rows,String kind,java.time.Instant start) {
        return rows.stream().filter(r->r.kind().equals(kind)&&r.start().equals(start)).findFirst().orElse(null);
    }
    /** 하는 일: 지표 값을 숫자로 바꿔요(JSON에서 읽으면 타입이 달라질 수 있어 문자열을 거쳐요). 받는 것: 값. 돌려주는 것: 숫자 또는 null이에요. */
    private static BigDecimal number(Object value){return value==null?null:new BigDecimal(value.toString());}
}
