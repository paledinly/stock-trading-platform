/*
 * KIS에서 받아 올 수 있는 시장 순위의 종류를 정의해요.
 * KisMarketRankingClient가 종류별로 다른 KIS 주소를 고르고, 시장 전체 스캔과 상한가 감시가 원하는 순위를 지정할 때 사용해요.
 */
package com.sunmo.stockplatform.kis.ranking;

/**
 * 시장 순위표의 종류예요. 신문 증권면의 "거래대금 상위", "상승률 상위" 같은 표 제목 목록이라고 생각하면 돼요.
 * TURNOVER 거래대금, VOLUME 거래량, PRICE_RISE 상승률, TRADE_STRENGTH 체결강도, HIGH_PROXIMITY 신고가 근접 순위예요.
 */
public enum RankingType {
    TURNOVER, VOLUME, PRICE_RISE, TRADE_STRENGTH, HIGH_PROXIMITY
}
