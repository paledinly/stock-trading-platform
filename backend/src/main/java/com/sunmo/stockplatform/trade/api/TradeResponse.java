/*
 * 거래 기록 한 건을 화면에 보낼 응답 모양으로 정의한 파일이에요.
 * 거래 자체 정보에 실현손익·보유일 계산 결과와 투자 노트를 함께 담아요.
 * TradeService가 만들어 TradeController가 돌려줘요.
 */
package com.sunmo.stockplatform.trade.api;

import com.sunmo.stockplatform.trade.application.PortfolioCalculator.Metrics;
import com.sunmo.stockplatform.trade.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 화면에 보여 줄 거래 한 건의 정보예요.
 * 가계부 한 줄에 "얼마에 몇 주, 그래서 얼마 벌었는지, 며칠 들고 있었는지, 왜 샀는지 메모"까지 붙여 보여 주는 요약 카드와 같아요.
 * 필드: id, 종목코드·이름·시장, 매수/매도, 거래시각, 가격, 수량, 금액, 실현손익, 보유일, 버전, 투자 노트.
 */
public record TradeResponse(Long id, String stockCode, String stockName, String market, String tradeType,
        Instant tradedAt, BigDecimal price, long quantity, BigDecimal amount, BigDecimal realizedPnl, long holdingDays,
        long version, Journal journal) {
    /** 응답에 붙는 투자 노트(메모, 목표가, 손절가, 이유 목록, 버전)예요. */
    public record Journal(String memo, BigDecimal targetPrice, BigDecimal stopLossPrice, List<Reason> reasons,
            long version) {
    }

    /** 응답에 붙는 매매 이유 한 개(분류 코드, 직접 쓴 설명)예요. */
    public record Reason(String code, String customReason) {
    }

    /**
     * 거래 엔티티, 노트, 손익 계산 결과를 하나의 응답으로 합쳐요.
     * 받는 것: 거래, 투자 노트(없으면 null), 계산 결과(없으면 null).
     * 돌려주는 것: 응답 객체. 계산 결과가 없으면 실현손익 0, 보유일 0으로 채워요.
     */
    public static TradeResponse from(Trade t, InvestmentJournal j, Metrics m) {
        return new TradeResponse(t.getId(), t.getStock().getStockCode(), t.getStock().getStockName(),
                t.getStock().getMarket().name(), t.getTradeType().name(), t.getTradedAt(), t.getPrice(),
                t.getQuantity(), t.getAmount(), m == null ? BigDecimal.ZERO : m.realizedPnl(),
                m == null ? 0 : m.holdingDays(), t.getVersion(),
                j == null ? null
                        : new Journal(j.getMemo(), j.getTargetPrice(), j.getStopLossPrice(),
                                j.getReasons().stream().map(r -> new Reason(r.getCode(), r.getCustomReason())).toList(),
                                j.getVersion()));
    }
}
