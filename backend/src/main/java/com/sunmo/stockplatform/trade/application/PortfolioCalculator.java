/*
 * 수동 거래 기록을 시간순으로 다시 훑어 종목별 보유 수량, 평균단가, 매도 시 실현손익, 보유일을 계산하는 파일이에요.
 * 보유 수량보다 많이 판 기록이 있으면 오류를 내서 장부가 앞뒤가 맞는지도 확인해요.
 * TradeService가 조회 응답을 만들 때와 저장 후 장부 검증을 할 때 사용해요.
 */
package com.sunmo.stockplatform.trade.application;

import com.sunmo.stockplatform.trade.domain.*;
import java.math.*;
import java.time.*;
import java.util.*;

/**
 * 거래 장부로 손익을 계산하는 계산기예요.
 * 가계부를 첫 장부터 넘기며 "지금 몇 주를 평균 얼마에 들고 있는지"를 적어 가다가, 판 날에는 번 돈을 따로 적어 두는 회계 담당과 같아요.
 * 평균단가 방식이고 수수료·세금은 반영하지 않아요.
 */
public class PortfolioCalculator {
    /** 거래 한 건의 계산 결과예요. realizedPnl은 실현손익(매수 건은 0), holdingDays는 보유 일수예요. */
    public record Metrics(BigDecimal realizedPnl, long holdingDays) {
    }

    /** 계산 중에 종목별로 들고 있는 상태(수량, 평균단가, 처음 산 시각)를 적어 두는 메모지예요. */
    private static final class Position {
        long quantity;
        BigDecimal average = BigDecimal.ZERO;
        Instant openedAt;
    }

    /**
     * 거래 목록을 시간순(같은 시각이면 id순)으로 재생해 거래별 실현손익과 보유일을 계산해요.
     * 받는 것: 거래 목록, 기준 현재 시각(아직 안 판 매수 건의 보유일 계산용).
     * 돌려주는 것: 거래 id → 계산 결과 지도(id가 없는 거래는 빠져요).
     * 보유 수량보다 많이 팔면 IllegalArgumentException을 던져요.
     */
    public Map<Long, Metrics> calculate(List<Trade> trades, Instant now) {
        Map<String, Position> positions = new HashMap<>();
        Map<Long, Metrics> result = new HashMap<>();
        for (Trade trade : trades.stream().sorted(Comparator.comparing(Trade::getTradedAt)
                .thenComparing(t -> t.getId() == null ? Long.MAX_VALUE : t.getId())).toList()) {
            Position p = positions.computeIfAbsent(trade.getStock().getStockCode(), ignored -> new Position());
            BigDecimal pnl = BigDecimal.ZERO;
            if (trade.getTradeType() == TradeType.BUY) {
                // 매수: (기존 총원가 + 이번 매수 금액) ÷ 새 수량으로 평균단가를 다시 구해요(소수 4자리 반올림).
                BigDecimal oldCost = p.average.multiply(BigDecimal.valueOf(p.quantity));
                p.quantity += trade.getQuantity();
                p.average = oldCost.add(trade.getAmount()).divide(BigDecimal.valueOf(p.quantity), 4,
                        RoundingMode.HALF_UP);
                if (p.openedAt == null)
                    p.openedAt = trade.getTradedAt();
            } else {
                // 매도: 들고 있는 것보다 많이 팔 수 없고, (매도가 - 평균단가) × 수량을 실현손익으로 잡아요.
                if (p.quantity < trade.getQuantity())
                    throw new IllegalArgumentException(
                            "Sell quantity exceeds the recorded position for " + trade.getStock().getStockCode());
                pnl = trade.getPrice().subtract(p.average).multiply(BigDecimal.valueOf(trade.getQuantity()));
                p.quantity -= trade.getQuantity();
            }
            // 보유일: 처음 산 시각부터 매도 건은 매도 시각까지, 매수 건은 지금까지의 일수예요.
            // ⚠️ 확인 필요: 매수 건은 나중에 전량 매도된 뒤에도 '지금'까지로 계산돼서, 이미 청산한 매수 기록의 보유일이 계속 늘어나는 것으로 보여요.
            long days = p.openedAt == null ? 0
                    : Math.max(0, Duration
                            .between(p.openedAt, trade.getTradeType() == TradeType.SELL ? trade.getTradedAt() : now)
                            .toDays());
            if (trade.getId() != null)
                result.put(trade.getId(), new Metrics(pnl, days));
            // 전량 매도로 포지션이 비면 평균단가와 시작 시각을 초기화해 다음 매수를 새 보유로 봐요.
            if (p.quantity == 0) {
                p.average = BigDecimal.ZERO;
                p.openedAt = null;
            }
        }
        return result;
    }
}
