/*
 * 종목 정보를 API 응답용 모양으로 바꿔 담는 파일이에요.
 * DB 엔티티를 그대로 내보내지 않고 화면에 필요한 값만 골라 담아요.
 * StockController의 검색 결과에서 사용해요.
 */
package com.sunmo.stockplatform.stock.api;

import com.sunmo.stockplatform.stock.domain.Stock;

import java.time.Instant;

/**
 * 화면에 보낼 종목 한 건의 정보예요.
 * 창고 장부(엔티티(DB 표의 한 줄과 연결된 객체))에서 손님에게 보여 줄 항목만 옮겨 적은 안내 카드와 같아요.
 * 필드: 종목코드, 종목명, 시장, 상품 유형, ETF/ETN 여부, 관리종목 여부, 거래정지 여부, 마스터 동기화 시각.
 */
public record StockResponse(
        String stockCode,
        String stockName,
        String market,
        String marketType,
        boolean etf,
        boolean etn,
        boolean managed,
        boolean tradingHalted,
        Instant masterSyncedAt) {
    /**
     * 종목 엔티티를 응답 객체로 옮겨 담아요.
     * 받는 것: 종목 엔티티. 돌려주는 것: 응답용 종목 정보(시장/유형은 문자열 이름으로 바꿔요).
     */
    public static StockResponse from(Stock stock) {
        return new StockResponse(stock.getStockCode(), stock.getStockName(), stock.getMarket().name(),
                stock.getMarketType().name(), stock.isEtf(), stock.isEtn(), stock.isManaged(),
                stock.isTradingHalted(), stock.getMasterSyncedAt());
    }
}
