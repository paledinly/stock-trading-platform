package com.sunmo.stockplatform.quote.application;

import com.sunmo.stockplatform.quote.domain.StockQuote;
import com.sunmo.stockplatform.stock.domain.Stock;

public interface QuoteProvider {
    StockQuote getQuote(Stock stock);
}

