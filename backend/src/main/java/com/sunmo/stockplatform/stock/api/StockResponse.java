package com.sunmo.stockplatform.stock.api;

import com.sunmo.stockplatform.stock.domain.Stock;

import java.time.Instant;

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
    public static StockResponse from(Stock stock) {
        return new StockResponse(stock.getStockCode(), stock.getStockName(), stock.getMarket().name(),
                stock.getMarketType().name(), stock.isEtf(), stock.isEtn(), stock.isManaged(),
                stock.isTradingHalted(), stock.getMasterSyncedAt());
    }
}
