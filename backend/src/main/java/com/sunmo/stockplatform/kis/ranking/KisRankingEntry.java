/*
 * KIS 순위 API 응답의 한 행(종목 하나)을 담는 데이터 묶음이에요.
 * 현재가, 등락률, 누적 거래량·거래대금, 체결강도, 시가·고가·저가를 함께 보관해요.
 * 시장 전체 스캔, 상한가 감시, 후보 Context 수집이 순위 결과를 읽을 때 사용해요.
 */
package com.sunmo.stockplatform.kis.ranking;

import java.math.BigDecimal;

/**
 * 순위표의 한 줄을 그대로 옮겨 적은 메모 카드 같은 기록이에요.
 * 값이 없는 칸은 0이 아니라 null로 남겨 "모름"을 구분해요.
 * 필드 값은 record 접근자(stockCode(), rank() 등)로 꺼내요.
 */
public record KisRankingEntry(String stockCode, String stockName, int rank, BigDecimal currentPrice,
        BigDecimal changeRate, Long accumulatedVolume, BigDecimal accumulatedTradingValue,
        BigDecimal tradeStrength, BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice) {
    /**
     * 시가·고가·저가 없이 만드는 짧은 생성자예요.
     * 받는 것: 종목코드, 이름, 순위, 현재가, 등락률, 누적 거래량, 누적 거래대금, 체결강도.
     * 돌려주는 것: 시가·고가·저가가 null인 KisRankingEntry. 예전 호출부(주로 테스트)와의 호환용으로 보여요.
     */
    public KisRankingEntry(String code, String name, int rank, BigDecimal price, BigDecimal rate,
            long volume, BigDecimal value, BigDecimal strength) {
        this(code, name, rank, price, rate, volume, value, strength, null, null, null);
    }
}
