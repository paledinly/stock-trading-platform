/*
 * 한 종목의 현재가 스냅샷(어느 한 순간의 시세 사진)을 담는 데이터 형식이에요.
 * 현재가, 전일 대비, 시가/고가/저가, 누적 거래량/거래대금과 시세 기준 시각을 함께 들고 있어요.
 * QuoteService, KIS 현재가 클라이언트, 시장 전체 스캔(marketwide) 등이 주고받아요.
 */
package com.sunmo.stockplatform.quote.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 종목 현재가 한 장을 표현하는 레코드(값만 담는 불변 데이터 묶음)예요.
 * 전광판에 찍힌 한 순간의 시세를 사진으로 찍어 둔 것처럼, 만든 뒤에는 바뀌지 않아요.
 *
 * <p>필드: stockCode(종목코드), stockName(종목명), market(시장 이름), currentPrice(현재가),
 * change(전일 종가 대비 변동 금액), changeRate(등락률, % 단위), openPrice/highPrice/lowPrice(당일 시가/고가/저가),
 * accumulatedVolume(당일 누적 거래량), accumulatedTradingValue(당일 누적 거래대금),
 * quotedAt(이 시세가 발생한 시각).
 */
public record StockQuote(
                String stockCode,
                String stockName,
                String market,
                BigDecimal currentPrice,
                BigDecimal change,
                BigDecimal changeRate,
                BigDecimal openPrice,
                BigDecimal highPrice,
                BigDecimal lowPrice,
                long accumulatedVolume,
                BigDecimal accumulatedTradingValue,
                Instant quotedAt) {
}
