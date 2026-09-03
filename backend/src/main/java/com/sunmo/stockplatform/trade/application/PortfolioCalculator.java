package com.sunmo.stockplatform.trade.application;

import com.sunmo.stockplatform.trade.domain.*;
import java.math.*;
import java.time.*;
import java.util.*;

public class PortfolioCalculator {
    public record Metrics(BigDecimal realizedPnl, long holdingDays) {
    }

    private static final class Position {
        long quantity;
        BigDecimal average = BigDecimal.ZERO;
        Instant openedAt;
    }

    public Map<Long, Metrics> calculate(List<Trade> trades, Instant now) {
        Map<String, Position> positions = new HashMap<>();
        Map<Long, Metrics> result = new HashMap<>();
        for (Trade trade : trades.stream().sorted(Comparator.comparing(Trade::getTradedAt)
                .thenComparing(t -> t.getId() == null ? Long.MAX_VALUE : t.getId())).toList()) {
            Position p = positions.computeIfAbsent(trade.getStock().getStockCode(), ignored -> new Position());
            BigDecimal pnl = BigDecimal.ZERO;
            if (trade.getTradeType() == TradeType.BUY) {
                BigDecimal oldCost = p.average.multiply(BigDecimal.valueOf(p.quantity));
                p.quantity += trade.getQuantity();
                p.average = oldCost.add(trade.getAmount()).divide(BigDecimal.valueOf(p.quantity), 4,
                        RoundingMode.HALF_UP);
                if (p.openedAt == null)
                    p.openedAt = trade.getTradedAt();
            } else {
                if (p.quantity < trade.getQuantity())
                    throw new IllegalArgumentException(
                            "Sell quantity exceeds the recorded position for " + trade.getStock().getStockCode());
                pnl = trade.getPrice().subtract(p.average).multiply(BigDecimal.valueOf(trade.getQuantity()));
                p.quantity -= trade.getQuantity();
            }
            long days = p.openedAt == null ? 0
                    : Math.max(0, Duration
                            .between(p.openedAt, trade.getTradeType() == TradeType.SELL ? trade.getTradedAt() : now)
                            .toDays());
            if (trade.getId() != null)
                result.put(trade.getId(), new Metrics(pnl, days));
            if (p.quantity == 0) {
                p.average = BigDecimal.ZERO;
                p.openedAt = null;
            }
        }
        return result;
    }
}
