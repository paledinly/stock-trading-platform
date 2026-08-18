package com.sunmo.stockplatform.trade.api;
import com.sunmo.stockplatform.trade.application.PortfolioCalculator.Metrics;
import com.sunmo.stockplatform.trade.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
public record TradeResponse(Long id,String stockCode,String stockName,String market,String tradeType,Instant tradedAt,BigDecimal price,long quantity,BigDecimal amount,BigDecimal realizedPnl,long holdingDays,long version,Journal journal){
 public record Journal(String memo,BigDecimal targetPrice,BigDecimal stopLossPrice,List<Reason> reasons,long version){}
 public record Reason(String code,String customReason){}
 public static TradeResponse from(Trade t,InvestmentJournal j,Metrics m){return new TradeResponse(t.getId(),t.getStock().getStockCode(),t.getStock().getStockName(),t.getStock().getMarket().name(),t.getTradeType().name(),t.getTradedAt(),t.getPrice(),t.getQuantity(),t.getAmount(),m==null?BigDecimal.ZERO:m.realizedPnl(),m==null?0:m.holdingDays(),t.getVersion(),j==null?null:new Journal(j.getMemo(),j.getTargetPrice(),j.getStopLossPrice(),j.getReasons().stream().map(r->new Reason(r.getCode(),r.getCustomReason())).toList(),j.getVersion()));}
}
