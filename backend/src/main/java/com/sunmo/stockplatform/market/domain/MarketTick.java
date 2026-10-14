/*
 * 실시간 체결 한 건(틱)을 나타내는 값 객체예요. 원천 거래일, 원천 체결시각, 가격, 체결량, 누적 거래량·거래대금 등을 담아요.
 * 만들 때 값이 잘못됐거나 거래일과 체결시각의 날짜가 다르면 바로 예외를 던져, 엉터리 틱이 안으로 들어오지 않게 해요.
 * KIS 실시간 파서가 만들고, MarketDataService·5분봉 집계기·Feature 엔진·Scanner 등 실시간 처리 전체가 사용해요.
 */
package com.sunmo.stockplatform.market.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 틱(체결이 한 번 일어날 때마다 오는 가격·수량 기록) 한 건이에요. 영수증 한 장처럼 "언제, 얼마에, 몇 주"가 적혀 있어요.
 * occurredAt은 거래소 원천 체결시각이고, 서버가 받은 시각은 여기에 없어요(ObservedMarketTick.receivedAt 참고).
 * tradeStrength(체결강도)부터 turnoverRate까지는 KIS가 주면 채우는 선택 항목이라 null일 수 있어요.
 */
public record MarketTick(String stockCode, LocalDate businessDate, Instant occurredAt, BigDecimal price,
        long tradeVolume, long cumulativeVolume, BigDecimal cumulativeTradingValue,
        long sequence, BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice,
        BigDecimal tradeStrength, Long cumulativeSellVolume, Long cumulativeBuyVolume,
        BigDecimal buyRatio, boolean tradingHalted, BigDecimal viStandardPrice,
        BigDecimal turnoverRate) {
    /**
     * 하는 일: 기본 항목만으로 틱을 만들어요. 선택 항목은 null, 거래정지는 false로 둬요.
     * 받는 것: 종목코드, 거래일, 체결시각, 가격, 체결량, 누적 거래량, 누적 거래대금, 순번.
     */
    public MarketTick(String stockCode, LocalDate businessDate, Instant occurredAt, BigDecimal price,
            long tradeVolume, long cumulativeVolume, BigDecimal cumulativeTradingValue,
            long sequence) {
        this(stockCode, businessDate, occurredAt, price, tradeVolume, cumulativeVolume, cumulativeTradingValue,
                sequence, null, null, null, null, null, null, null, false, null, null);
    }

    /**
     * 하는 일: 필수 값이 비었거나 가격이 0 이하이거나 수량이 음수이면 예외를 던져요.
     * 또 거래일이 체결시각의 서울 날짜와 다르면 예외를 던져, 과거·미래 날짜가 섞이지 않게 해요.
     */
    public MarketTick {
        if (stockCode == null || stockCode.isBlank() || businessDate == null || occurredAt == null
                || price == null || price.signum() <= 0 || tradeVolume < 0 || cumulativeVolume < 0)
            throw new IllegalArgumentException("Invalid market tick");
        LocalDate eventDate = occurredAt.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate();
        if (!businessDate.equals(eventDate))
            throw new IllegalArgumentException("Market tick business date does not match occurredAt");
    }
}
