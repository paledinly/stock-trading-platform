/*
 * 일봉(하루 한 개의 가격 막대)으로 계산한 이동평균(MA5/MA20/MA60: 최근 5/20/60일 종가의 평균) 결과를 담는 record예요.
 * 종가가 평균보다 얼마나 위에 있는지, MA20이 오르는 중인지 같은 판단 재료를 한 묶음으로 들고 다녀요.
 * DailyMovingAverageService가 만들고, 정밀 후보 평가(ClosingPrecisionEvaluator)와 백테스트(OvernightBacktestService)가 점수 재료로 사용해요.
 */
package com.sunmo.stockplatform.closing.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 한 종목의 일봉 이동평균 계산 결과를 담는 값 묶음이에요.
 * 건강검진 결과표처럼 "평균보다 얼마나 높은지, 추세가 오르는지"를 항목별로 적어 둔 종이라고 생각하면 돼요.
 * 거리율(DistanceRate)은 (종가-평균)/평균×100 퍼센트이고, 일봉이 20개 미만이면 ready=false인 빈 결과예요.
 */
public record DailyMovingAverageFeature(
        boolean ready,
        int candleCount,
        LocalDate asOfDate,
        BigDecimal lastClose,
        BigDecimal ma5,
        BigDecimal ma20,
        BigDecimal ma60,
        BigDecimal ma5DistanceRate,
        BigDecimal ma20DistanceRate,
        BigDecimal ma60DistanceRate,
        BigDecimal ma20SlopeRate,
        boolean closeAboveMa20,
        boolean ma5AboveMa20,
        boolean ma20Rising,
        boolean bullishAlignment,
        boolean overextendedFromMa20) {
    /**
     * 하는 일: 일봉이 부족해 계산을 못 했을 때 쓰는 빈 결과를 만들어요.
     * 받는 것: 실제로 모인 일봉 개수.
     * 돌려주는 것: ready=false, 숫자 값은 모두 null, 판단 값은 모두 false인 결과.
     */
    public static DailyMovingAverageFeature empty(int candleCount) {
        return new DailyMovingAverageFeature(false, candleCount, null, null, null, null, null, null, null, null, null,
                false, false, false, false, false);
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
        values.put("asOfDate", asOfDate == null ? "" : asOfDate.toString());
        values.put("lastClose", plain(lastClose));
        values.put("ma5", plain(ma5));
        values.put("ma20", plain(ma20));
        values.put("ma60", plain(ma60));
        values.put("ma5DistanceRate", plain(ma5DistanceRate));
        values.put("ma20DistanceRate", plain(ma20DistanceRate));
        values.put("ma60DistanceRate", plain(ma60DistanceRate));
        values.put("ma20SlopeRate", plain(ma20SlopeRate));
        values.put("closeAboveMa20", closeAboveMa20);
        values.put("ma5AboveMa20", ma5AboveMa20);
        values.put("ma20Rising", ma20Rising);
        values.put("bullishAlignment", bullishAlignment);
        values.put("overextendedFromMa20", overextendedFromMa20);
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
