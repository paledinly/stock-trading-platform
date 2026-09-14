package com.sunmo.stockplatform.marketwide.domain;

import com.sunmo.stockplatform.kis.ranking.RankingType;
import com.sunmo.stockplatform.stock.domain.Stock;

import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.time.Instant;
import com.sunmo.stockplatform.kis.ranking.KisRankingEntry;

public record BroadCandidate(Stock stock, BigDecimal rankingScore, Map<RankingType, Integer> ranks,
        BigDecimal tradeStrength, List<RankingObservation> observations) {
    public BroadCandidate(Stock stock, BigDecimal score, Map<RankingType, Integer> ranks, BigDecimal strength) {
        this(stock, score, ranks, strength, List.of());
    }
    public record RankingObservation(RankingType type, KisRankingEntry entry, Instant receivedAt) { }
}
