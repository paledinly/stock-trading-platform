package com.sunmo.stockplatform.kis.master;

import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.domain.MarketType;

public record MasterStock(
        String stockCode,
        String standardCode,
        String stockName,
        Market market,
        MarketType marketType,
        boolean managed,
        boolean tradingHalted
) {
}

