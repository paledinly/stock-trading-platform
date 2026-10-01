package com.sunmo.stockplatform.kis.ranking;

import java.time.Instant;
import java.util.List;
import com.sunmo.stockplatform.stock.domain.Market;

public record ObservedRanking(RankingType type, Market market, Instant receivedAt, List<KisRankingEntry> entries) {}
