/*
 * 장중 5분봉(5분마다 하나씩 생기는 가격 막대)으로 계산한 이동평균(MA5/MA20/MA60: 최근 5/20/60개 봉 종가의 평균) 결과를 담는 record예요.
 * 정배열, 골든크로스, MA20 지지/이탈 같은 단기 추세 신호를 한 묶음으로 들고 다녀요.
 * IntradayMovingAverageService가 만들고, 정밀 후보 평가(ClosingPrecisionEvaluator), 백테스트, 보유 판단(OvernightPositionDecisionService)이 사용해요.
 */
package com.sunmo.stockplatform.closing.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 한 종목의 5분봉 이동평균 계산 결과를 담는 값 묶음이에요.
 * 달리기 선수의 최근 구간 기록표처럼 "지금 속도가 최근 평균보다 빠른지, 막 속도가 붙었는지"를 적어 둔 표라고 생각하면 돼요.
 * 5분봉이 20개 미만이면 ready=false인 빈 결과예요.
 */
public record IntradayMovingAverageFeature(
        boolean ready,
        int candleCount,
        BigDecimal lastClose,
        BigDecimal ma5,
        BigDecimal ma20,
        BigDecimal ma60,
        BigDecimal ma5DistanceRate,
        BigDecimal ma20DistanceRate,
        BigDecimal ma60DistanceRate,
        boolean bullishAlignment,
        boolean goldenCross,
        boolean ma20Support,
        boolean ma20Broken) {
    /**
     * 하는 일: 5분봉이 부족해 계산을 못 했을 때 쓰는 빈 결과를 만들어요.
     * 받는 것: 실제로 모인 5분봉 개수.
     * 돌려주는 것: ready=false, 숫자 값은 모두 null, 신호 값은 모두 false인 결과.
     */
    public static IntradayMovingAverageFeature empty(int candleCount) {
        return new IntradayMovingAverageFeature(false, candleCount, null, null, null, null, null, null, null,
                false, false, false, false);
    }

    /**
     * 하는 일: 결과를 저장·응답용 키-값 지도로 바꿔요(입력 순서를 지켜요).
     * 받는 것: 없음.
     * 돌려주는 것: 숫자는 소수 문자열, 값이 없으면 빈 문자열("")로 채운 Map.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ready", ready);
        values.put("candleCount", candleCount);
        values.put("lastClose", plain(lastClose));
        values.put("ma5", plain(ma5));
        values.put("ma20", plain(ma20));
        values.put("ma60", plain(ma60));
        values.put("ma5DistanceRate", plain(ma5DistanceRate));
        values.put("ma20DistanceRate", plain(ma20DistanceRate));
        values.put("ma60DistanceRate", plain(ma60DistanceRate));
        values.put("bullishAlignment", bullishAlignment);
        values.put("goldenCross", goldenCross);
        values.put("ma20Support", ma20Support);
        values.put("ma20Broken", ma20Broken);
        return values;
    }

    /**
     * 하는 일: BigDecimal 숫자를 지수 표기 없는 문자열로 바꿔요.
     * 받는 것: 숫자(없을 수 있음).
     * 돌려주는 것: 숫자 문자열, null이면 빈 문자열.
     */
    private String plain(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }
}
