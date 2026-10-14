/*
 * 한 종목의 실시간 Feature(판단용 지표)를 한 틱 시점에 찍어 둔 값 묶음이에요.
 * MarketFeatureEngine이 틱마다 만들고, ObservedMarketTick에 실려 Scanner·장마감 추천·연구용 수집기로 전달돼요.
 */
package com.sunmo.stockplatform.market.feature.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 틱 하나가 도착했을 때 계산된 지표 사진이에요. 건강검진 결과표처럼 여러 수치가 한 시점 기준으로 적혀 있어요.
 * 비율(…Rate) 값은 퍼센트예요. 진행 중인 5분 칸 기준 값이라 확정봉 값과 같은 뜻으로 비교하면 안 돼요.
 * - vwap: 거래량 가중 평균가격(오늘 거래대금 ÷ 거래량), vwapDistanceRate: 현재가가 VWAP보다 몇 % 위/아래인지
 * - vwapSlopeRate: 직전 틱 대비 VWAP 변화율(일정 시간창 기울기가 아니에요)
 * - volumeRatio: 현재 5분 칸 거래량 ÷ 직전 6칸 평균, turnoverRatio: 같은 방식의 거래대금 배수(회전율이 아니에요)
 * - buyVolumeDelta/sellVolumeDelta: 직전 틱 대비 누적 매수/매도 체결량 증분
 * - dayHighDistanceRate: 당일 고가보다 몇 % 아래인지
 * featureVersion은 계산 방식 버전이에요(현재 market-feature-v2).
 */
public record MarketFeatureSnapshot(
        String stockCode,
        LocalDate businessDate,
        Instant occurredAt,
        BigDecimal price,
        long cumulativeVolume,
        BigDecimal cumulativeTradingValue,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal vwap,
        BigDecimal vwapDistanceRate,
        BigDecimal vwapSlopeRate,
        BigDecimal volumeRatio,
        BigDecimal turnoverRatio,
        BigDecimal tradeStrength,
        long buyVolumeDelta,
        long sellVolumeDelta,
        BigDecimal buyRatio,
        BigDecimal dayHighDistanceRate,
        boolean tradingHalted,
        BigDecimal viStandardPrice,
        String featureVersion) {
    /** 현재 Feature 계산 방식의 버전 이름이에요. 계산식을 바꾸면 이 값도 바꿔 과거 값과 섞이지 않게 해요. */
    public static final String VERSION = "market-feature-v2";
}
