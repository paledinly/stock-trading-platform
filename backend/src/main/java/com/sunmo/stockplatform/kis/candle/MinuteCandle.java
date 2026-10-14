/*
 * KIS 당일 분봉 조회 결과 한 줄(1분 구간의 시가, 고가, 저가, 종가, 체결량, 누적 거래대금)을 담는 파일이에요.
 * KisMinuteCandleClient가 만들고, candle 패키지의 분봉 보충(5분봉 메우기) 쪽에서 쓰는 것으로 보여요.
 */
package com.sunmo.stockplatform.kis.candle;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 1분 구간의 가격 요약(분봉)을 담는 값 객체예요.
 * 1분마다 찍은 가격 기록 카드 한 장이라고 생각하면 돼요.
 * startTime(KIS가 준 체결 시각을 서울 시간으로 해석한 구간 시각), open/high/low/close, volume(그 분의 체결량),
 * cumulativeTradingValue(그 시각까지의 누적 거래대금, 없으면 null)를 돌려줘요.
 */
public record MinuteCandle(Instant startTime, BigDecimal open, BigDecimal high, BigDecimal low,
        BigDecimal close, long volume, BigDecimal cumulativeTradingValue) {
}
