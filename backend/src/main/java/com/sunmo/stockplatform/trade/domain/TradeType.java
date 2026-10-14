/*
 * 거래가 매수(BUY)인지 매도(SELL)인지 나타내는 파일이에요.
 * 거래 기록, 요청 검증, 손익 계산에서 사용해요.
 */
package com.sunmo.stockplatform.trade.domain;

/**
 * 거래 방향 구분이에요.
 * 가계부에서 "들어온 물건 / 나간 물건"을 표시하는 칸처럼, 산 기록인지 판 기록인지 알려 줘요.
 */
public enum TradeType {
    BUY, SELL
}
