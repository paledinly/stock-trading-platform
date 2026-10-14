/*
 * 추천 종목을 다음 날 장중에 "어떻게 할지" 판단한 결과의 종류(enum)예요.
 * OvernightPositionDecisionService가 현재가·VWAP·체결강도·이동평균을 보고 이 중 하나를 골라요. 실제 주문은 내지 않아요.
 */
package com.sunmo.stockplatform.closing.domain;

/**
 * 보유 판단 종류예요.
 * DATA_PENDING: 판단할 자료가 아직 없음, HOLD: 그대로 보유, EXTEND_HOLD: 목표 도달했고 추세도 건강해 더 보유,
 * TAKE_PROFIT: 목표 도달해 이익 실현, SELL_WARNING: 추세 약화 신호(VWAP·5분봉 20개 이동평균 이탈, 약한 체결강도, 고가 대비 하락), STOP_LOSS: 손절선 도달.
 * 신호등처럼 지금 상황에 맞는 색 하나를 켜 주는 역할이에요(VWAP: 거래량 가중 평균가).
 */
public enum OvernightPositionDecisionType {
    DATA_PENDING,
    HOLD,
    EXTEND_HOLD,
    TAKE_PROFIT,
    SELL_WARNING,
    STOP_LOSS
}
