/*
 * 5분봉 집계기가 내놓는 봉 한 개의 순간 모습(스냅샷)이에요.
 * FiveMinuteCandleAggregator가 만들고, MarketDataService가 저장·Scanner 평가·SSE 발행에 사용해요.
 */
package com.sunmo.stockplatform.candle.application;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 봉(5분 동안의 시가·고가·저가·종가·거래량 묶음) 한 개를 특정 순간에 찍은 사진이에요.
 * finalCandle이 false면 아직 진행 중인 봉이라 값이 더 바뀔 수 있고, true면 확정봉이에요.
 * revision은 수정 번호인데, 현재 집계기는 이 값을 올리지 않아 항상 0인 것으로 보여요.
 */
public record CandleSnapshot(String stockCode, Instant startTime, BigDecimal open, BigDecimal high, BigDecimal low,
        BigDecimal close, long volume, BigDecimal tradingValue, boolean finalCandle, int revision) {
}
