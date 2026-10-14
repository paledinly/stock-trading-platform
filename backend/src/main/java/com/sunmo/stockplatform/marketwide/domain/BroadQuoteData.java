/*
 * BROAD 스냅샷에 넣을 시세 값(가격, 등락률, 거래량, 거래대금, 시가·고가·저가, 체결강도)을 한데 담아요.
 * 시세가 REST 조회·실시간 캐시(자주 쓰는 값을 잠시 보관해 두는 저장소)·순위표 중 어디서 왔는지도 source로 함께 남겨요.
 * BroadQuoteResolver, BroadSnapshotService, MarketBroadSnapshot이 사용해요.
 */
package com.sunmo.stockplatform.marketwide.domain;

import com.sunmo.stockplatform.quote.domain.StockQuote;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 출처가 표시된 BROAD용 시세 묶음이에요. 값이 없으면 null로 비워 두고, 없는 값을 지어내지 않아요.
 * (원문: Nullable market fields: a ranking-only observation must not invent OHLC or change values.)
 * 영수증에 적힌 항목만 장부에 옮기고, 안 적힌 칸은 빈칸으로 두는 꼼꼼한 경리라고 생각하면 돼요.
 */
public record BroadQuoteData(BigDecimal price, BigDecimal changeRate, Long volume, BigDecimal tradingValue,
        BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal tradeStrength, Instant observedAt, String source) {
    /**
     * 하는 일: 핵심 4개 값(양수 가격, 등락률, 0 이상 거래량, 0 이상 거래대금)이 모두 있는지 확인해요.
     * 받는 것: 없음(이 레코드의 값을 봐요).
     * 돌려주는 것: 모두 갖춰졌으면 true, 하나라도 빠지면 false.
     */
    public boolean complete() {
        return price != null && price.signum() > 0 && changeRate != null && volume != null && volume >= 0
                && tradingValue != null && tradingValue.signum() >= 0;
    }
    /**
     * 하는 일: 일반 시세 객체(StockQuote)를 BROAD용 시세 묶음으로 바꿔요. 시세 객체엔 체결강도가 없어서 null로 둬요.
     * 받는 것: 시세 객체, 출처 이름(예: "REST", "CACHE", "REALTIME_CACHE").
     * 돌려주는 것: 변환된 BroadQuoteData.
     */
    public static BroadQuoteData fromQuote(StockQuote quote, String source) {
        return new BroadQuoteData(quote.currentPrice(), quote.changeRate(), quote.accumulatedVolume(),
                quote.accumulatedTradingValue(), quote.openPrice(), quote.highPrice(), quote.lowPrice(), null,
                quote.quotedAt(), source);
    }
    /**
     * 하는 일: 순위표에서 받은 한 줄(관측값)을 BROAD용 시세 묶음으로 바꿔요.
     * 관측 시각은 순위표를 받은 시각이고, 출처는 "RANKING:순위표종류"로 적어요.
     * 받는 것: 순위 관측값.
     * 돌려주는 것: 변환된 BroadQuoteData.
     */
    public static BroadQuoteData fromRanking(BroadCandidate.RankingObservation observation) {
        var row = observation.entry();
        return new BroadQuoteData(row.currentPrice(), row.changeRate(), row.accumulatedVolume(),
                row.accumulatedTradingValue(), row.openPrice(), row.highPrice(), row.lowPrice(), row.tradeStrength(),
                observation.receivedAt(), "RANKING:" + observation.type().name());
    }
    /**
     * 하는 일: 핵심 4개 값(가격, 등락률, 거래량, 거래대금) 중 몇 개가 채워졌는지 세요.
     * 여러 출처 중 가장 많이 채워진 시세를 고를 때 써요.
     * 받는 것: 없음.
     * 돌려주는 것: 0~4 사이의 개수.
     */
    public int availableCoreFields() {
        return (price != null && price.signum() > 0 ? 1 : 0) + (changeRate == null ? 0 : 1)
                + (volume == null ? 0 : 1) + (tradingValue == null ? 0 : 1);
    }
}
