package com.sunmo.stockplatform.marketwide.domain;

import com.sunmo.stockplatform.kis.ranking.RankingType;
import com.sunmo.stockplatform.stock.domain.Stock;

import java.math.BigDecimal;
import java.util.Map;

public record BroadCandidate(Stock stock, BigDecimal rankingScore, Map<RankingType, Integer> ranks,
        BigDecimal tradeStrength) {
}
