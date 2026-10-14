/*
 * 외부에서 현재가를 받아 오는 방법을 정해 둔 약속(인터페이스)이에요.
 * 실제 구현은 KIS REST 현재가를 부르는 KisQuoteClient예요.
 * QuoteService가 기준 시세(baseline)를 가져올 때 사용해요.
 */
package com.sunmo.stockplatform.quote.application;

import com.sunmo.stockplatform.quote.domain.StockQuote;
import com.sunmo.stockplatform.stock.domain.Stock;

/**
 * 종목의 현재가를 가져오는 공급자 약속이에요.
 * 콘센트 규격처럼 "꽂으면 시세가 나온다"는 모양만 정해 두고, 어느 회사(KIS 등) 플러그를 꽂을지는 바꿀 수 있게 해 줘요.
 */
public interface QuoteProvider {
    /**
     * 받는 것: 종목 정보. 돌려주는 것: 그 종목의 현재가 스냅샷. 실패하면 예외를 던지는 것으로 보여요.
     */
    StockQuote getQuote(Stock stock);
}
