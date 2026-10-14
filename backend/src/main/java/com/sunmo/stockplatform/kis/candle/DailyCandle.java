/*
 * KIS 일봉 조회 결과 한 줄(하루치 시가, 고가, 저가, 종가, 거래량, 거래대금)을 담는 파일이에요.
 * KisDailyCandleClient가 만들고, candle 패키지의 일봉 보충과 수정주가 수집 쪽에서 받아 저장하는 것으로 보여요.
 */
package com.sunmo.stockplatform.kis.candle;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 하루치 가격 요약(일봉)을 담는 값 객체예요.
 * 가계부 한 줄에 "날짜, 아침 가격, 최고, 최저, 마감 가격, 거래 수량, 거래 금액"을 적어 둔 것과 같아요.
 * date(거래일), open/high/low/close(시가/고가/저가/종가), volume(누적 거래량), tradingValue(누적 거래대금)를 돌려줘요.
 */
public record DailyCandle(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low,
        BigDecimal close, long volume, BigDecimal tradingValue) { }
